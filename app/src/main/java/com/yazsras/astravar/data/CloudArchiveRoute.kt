package com.yazsras.astravar.data

import java.net.URLDecoder
import java.net.URLEncoder

internal fun validArchiveSuffix(suffix:String):Boolean {
    if(!suffix.startsWith("archive?") || suffix.length>1600 || suffix.contains('#'))return false
    val query=suffix.substringAfter('?')
    if(query.startsWith("id="))return cloudIdentifier.matches(query.removePrefix("id="))
    val fields=linkedMapOf<String,String>()
    for(pair in query.split('&')){
        val key=pair.substringBefore('=')
        if(key !in setOf("limit","kind","query","after","atEpoch","atRevision","grantRevision") || key in fields || !pair.contains('='))return false
        val value=try{URLDecoder.decode(pair.substringAfter('='),"UTF-8")}catch(_:IllegalArgumentException){return false}
        fields[key]=value
    }
    if(fields["limit"]!="25" || (fields.containsKey("after") && !fields.keys.containsAll(setOf("atEpoch","atRevision","grantRevision"))))return false
    return fields.all{(key,value)->when(key){
        "kind","after"->cloudIdentifier.matches(value)
        "query"->value.length<=120 && value.none(Char::isISOControl)
        else->value.toLongOrNull()?.let { it>=0 }==true
    }}
}
internal fun cloudArchiveSuffix(version:CloudVersion,kind:String?=null,query:String="",after:String?=null):String {
    val params=linkedMapOf("limit" to "25")
    kind?.let{params["kind"]=it}
    if(query.isNotBlank())params["query"]=query
    if(after!=null){params["after"]=after;params["atEpoch"]=version.epoch.toString();params["atRevision"]=version.revision.toString();params["grantRevision"]=version.grantRevision.toString()}
    return ("archive?"+params.entries.joinToString("&"){(k,v)->"$k=${URLEncoder.encode(v,"UTF-8")}" }).also {require(validArchiveSuffix(it))}
}
