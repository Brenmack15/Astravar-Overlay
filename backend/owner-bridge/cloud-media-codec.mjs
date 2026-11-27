import { cloudFail } from './cloud-validation.mjs';

// This portable Worker codec accepts static, non-interlaced 8-bit PNG images.
// Bound compressed input, expansion, chunk count, dimensions and stream output
// independently. A file extension, IHDR, or image/* header alone proves nothing.
export const MEDIA_MAX_BYTES = 4 * 1024 * 1024;
const maxPixels = 2 * 1024 * 1024;
const thumbEdge = 160;
const signature = Uint8Array.of(137,80,78,71,13,10,26,10);
const channels = new Map([[0,1],[2,3],[3,1],[4,2],[6,4]]);
const crcTable = Uint32Array.from({length:256}, (_, index) => {
  let value = index;
  for (let bit=0; bit<8; bit++) value = value & 1 ? (value >>> 1) ^ 0xedb88320 : value >>> 1;
  return value >>> 0;
});

function invalid() { cloudFail(422, 'INVALID_OR_UNSUPPORTED_PNG'); }
function crc32(bytes) {
  let crc = 0xffffffff;
  for (const byte of bytes) crc = crcTable[(crc ^ byte) & 255] ^ (crc >>> 8);
  return (crc ^ 0xffffffff) >>> 0;
}
export function mediaHex(buffer) {
  return Array.from(new Uint8Array(buffer), n => n.toString(16).padStart(2, '0')).join('');
}
export async function mediaDigest(bytes) { return mediaHex(await crypto.subtle.digest('SHA-256', bytes)); }

export async function boundedMediaBody(stream, limit) {
  if (!stream) invalid();
  const reader = stream.getReader();
  const parts = [];
  let size = 0, chunks = 0;
  try {
    for (;;) {
      const {done, value} = await reader.read();
      if (done) break;
      if (!(value instanceof Uint8Array) || ++chunks>65536 || value.byteLength > limit-size) {
        await reader.cancel().catch(() => {});
        cloudFail(413, 'IMAGE_SIZE_LIMIT');
      }
      if (value.byteLength) { parts.push(value); size += value.byteLength; }
    }
  } finally { reader.releaseLock(); }
  const bytes = new Uint8Array(size);
  let offset = 0;
  for (const part of parts) { bytes.set(part, offset); offset += part.length; }
  return bytes;
}

function pngChunk(type, payload) {
  const bytes = new Uint8Array(12 + payload.length), view = new DataView(bytes.buffer);
  view.setUint32(0, payload.length);
  bytes.set(new TextEncoder().encode(type), 4);
  bytes.set(payload, 8);
  view.setUint32(8+payload.length, crc32(bytes.subarray(4, 8+payload.length)));
  return bytes;
}
export async function encodeMediaPng(width, height, rgba) {
  const stride = 4*width;
  const source = new Uint8Array(height*(stride+1));
  for (let y=0; y<height; y++) source.set(rgba.subarray(y*stride,(y+1)*stride),y*(stride+1)+1);
  const compressed = new Uint8Array(await new Response(new Blob([source]).stream().pipeThrough(new CompressionStream('deflate'))).arrayBuffer());
  const header = new Uint8Array(13), v = new DataView(header.buffer);
  v.setUint32(0,width); v.setUint32(4,height); header[8]=8; header[9]=6;
  return new Uint8Array(await new Blob([signature,pngChunk('IHDR',header),pngChunk('IDAT',compressed),pngChunk('IEND',new Uint8Array())]).arrayBuffer());
}
function paeth(a,b,c) {
  const p=a+b-c, pa=Math.abs(p-a), pb=Math.abs(p-b), pc=Math.abs(p-c);
  if (pa<=pb && pa<=pc) return a;
  return pb<=pc ? b : c;
}

export async function inspectMediaPng(bytes) {
  if (bytes.length>MEDIA_MAX_BYTES) cloudFail(413,'IMAGE_SIZE_LIMIT');
  if (bytes.length<45 || !signature.every((x,i)=>bytes[i]===x)) invalid();
  const view=new DataView(bytes.buffer,bytes.byteOffset,bytes.byteLength);
  const idats=[], seen=new Set();
  let width, height, color, palette, transparency, end=false, closedData=false, count=0;
  for (let pos=8; pos<bytes.length;) {
    if (++count>2048 || pos+12>bytes.length || end) invalid();
    const size=view.getUint32(pos), next=pos+12+size;
    if (next>bytes.length) invalid();
    const type=String.fromCharCode(...bytes.subarray(pos+4,pos+8)), payload=bytes.subarray(pos+8,next-4);
    if (view.getUint32(next-4)!==crc32(bytes.subarray(pos+4,next-4))) invalid();
    if (count===1 && type!=='IHDR' || (type!=='IDAT' && seen.has(type))) invalid();
    if(idats.length && type!=='IDAT')closedData=true;
    switch(type){
      case 'IHDR':
        if(count!==1 || size!==13)invalid();
        width=view.getUint32(pos+8); height=view.getUint32(pos+12); color=payload[9];
        if(!width||!height||width>4096||height>4096||width*height>maxPixels)cloudFail(413,'IMAGE_DIMENSION_LIMIT');
        if(payload[8]!==8||!channels.has(color)||payload[10]||payload[11]||payload[12])invalid();
        break;
      case 'PLTE':
        if (idats.length || !size || size%3 || size>768 || color===0 || color===4) invalid();
        palette=payload; break;
      case 'tRNS':
        if (idats.length || (color===0 ? size!==2 : color===2 ? size!==6 : color===3 ? !palette||!size||size>palette.length/3 : true)) invalid();
        transparency=payload; break;
      case 'IDAT':
        if (closedData || color===3&&!palette) invalid();
        idats.push(payload); break;
      case 'IEND':
        if(size!==0 || !idats.length || next!==bytes.length)invalid();
        end=true; break;
      case 'sBIT': {
        // Android's PNG encoder records the original precision in this metadata.
        // Indexed images describe three palette channels even though pixels store one index.
        const count = color===3 ? 3 : channels.get(color);
        if (size!==count || palette || idats.length || payload.some(precision=>precision<1 || precision>8)) invalid();
        break;
      }
      case 'sRGB': if (size!==1||payload[0]>3||idats.length) invalid(); break;
      case 'gAMA': if(size!==4||idats.length)invalid(); break;
      case 'pHYs': if(size!==9||payload[8]>1||idats.length)invalid(); break;
      case 'cHRM': if(size!==32||idats.length)invalid(); break;
      case 'tIME': if(size!==7)invalid(); break;
      default: invalid();
    }
    seen.add(type); pos=next;
  }
  if(!end)invalid();
  const bpp=channels.get(color), stride=width*bpp, expected=height*(stride+1);
  let raw;
  try { raw=await boundedMediaBody(new Blob(idats).stream().pipeThrough(new DecompressionStream('deflate')),expected); }
  catch(error) { if(error?.code==='IMAGE_SIZE_LIMIT')throw error; invalid(); }
  if(raw.length!==expected)invalid();
  const scale=Math.min(1,thumbEdge/Math.max(width,height));
  const tw=Math.max(1,Math.round(width*scale)), th=Math.max(1,Math.round(height*scale));
  const pixels=new Uint8Array(tw*th*4), sample=new Map();
  for(let y=0;y<th;y++)sample.set(Math.floor(y*height/th),y);
  const alpha=transparency ? new DataView(transparency.buffer,transparency.byteOffset,transparency.byteLength) : null;
  for(let y=0;y<height;y++){
    const row=y*(stride+1)+1, filter=raw[row-1];
    if(filter>4)invalid();
    for(let i=0;i<stride;i++){
      const left=i>=bpp?raw[row+i-bpp]:0,up=y?raw[row+i-stride-1]:0,ul=y&&i>=bpp?raw[row+i-stride-1-bpp]:0;
      let extra=0;
      switch(filter){
        case 1: extra=left; break;
        case 2: extra=up; break;
        case 3: extra=(left+up)>>>1; break;
        case 4: extra=paeth(left,up,ul); break;
      }
      raw[row+i]=(raw[row+i]+extra)&255;
      if(color===3 && raw[row+i]>=palette.length/3)invalid();
    }
    if(!sample.has(y))continue;
    for(let x=0;x<tw;x++){
      const p=row+Math.floor(x*width/tw)*bpp, i=(sample.get(y)*tw+x)*4;
      let r,g,b,a=255;
      switch(color){
        case 0: r=g=b=raw[p]; if(alpha&&alpha.getUint16(0)===r)a=0; break;
        case 2: r=raw[p];g=raw[p+1];b=raw[p+2];if(alpha&&alpha.getUint16(0)===r&&alpha.getUint16(2)===g&&alpha.getUint16(4)===b)a=0;break;
        case 3: {const index=raw[p];r=palette[3*index];g=palette[3*index+1];b=palette[3*index+2];a=transparency?.[index]??255;break;}
        case 4: r=g=b=raw[p];a=raw[p+1];break;
        default: r=raw[p];g=raw[p+1];b=raw[p+2];a=raw[p+3];
      }
      pixels[i]=r;pixels[i+1]=g;pixels[i+2]=b;pixels[i+3]=a;
    }
  }
  const thumbnail=await encodeMediaPng(tw,th,pixels);
  return {meta:{mime:'image/png',sha256:await mediaDigest(bytes),byteLength:bytes.length,width,height,
    thumbnailSha256:await mediaDigest(thumbnail),thumbnailByteLength:thumbnail.length,thumbnailWidth:tw,thumbnailHeight:th},thumbnail};
}
