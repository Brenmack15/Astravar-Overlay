import {must} from './cloud-mechanics-core.mjs';

export function checkedClassTracks(level,tracks){
  must(tracks.length>0 && new Set(tracks.map(t=>t.classId)).size===tracks.length,'CLASS_IDENTITIES_INVALID');
  must(tracks.reduce((n,t)=>n+t.level,0)===level,'CLASS_LEVELS_MUST_MATCH_ACTOR');
  return tracks.map(t=>({...t}));
}
export function applyClassGain(before,x,next){
  const tracks=before.classTracks,gain=x.classGain;
  if(!tracks){must(!gain,'REVIEW_CLASS_BASELINE_FIRST');return null;}
  checkedClassTracks(before.level,tracks);
  must(gain,'CHOOSE_ADVANCING_CLASS');
  const old=tracks.find(t=>t.classId===gain.classId);
  must((old?.level??0)===gain.fromClassLevel,'CLASS_LEVEL_CHANGED');
  must(Boolean(old)!==Boolean(gain.newClass),'EXISTING_CLASS_PIN_OR_NEW_CLASS_SOURCE_REQUIRED');
  must(!gain.subclass||!old?.subclass,'SUBCLASS_ALREADY_PINNED');
  const selected=old??{classId:gain.classId,...gain.newClass,level:0};
  const advanced={...selected,level:selected.level+1,...(gain.subclass?{subclass:gain.subclass}:{})};
  next.classTracks=checkedClassTracks(x.toLevel,old?tracks.map(t=>t===old?advanced:t):[...tracks,advanced]);
  return {classId:gain.classId,before:old??null,after:advanced};
}
