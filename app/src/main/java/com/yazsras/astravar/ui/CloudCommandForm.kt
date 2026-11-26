package com.yazsras.astravar.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.*

internal fun cloudLabel(raw: String): String = raw.replace('_',' ')
    .replace(Regex("([a-z])([A-Z])"), "$1 $2").replaceFirstChar { it.uppercase() }

/** Fields come from the server's closed operation schema. Nothing is submitted by opening a field or choosing a variant. */
@Composable internal fun CloudCommandForm(operation: JsonObject, busy: Boolean, dismiss: () -> Unit,
    submit: (JsonObject) -> Unit, error: String?, initial: JsonObject = buildJsonObject {}) {
    val name = operation.getValue("name").jsonPrimitive.content
    val schema = operation.getValue("schema").jsonObject
    var draft by rememberSaveable(name,initial.toString()) { mutableStateOf(initial.toString()) }
    val args = remember(draft) { Json.parseToJsonElement(draft).jsonObject }
    PlayerSheet(cloudLabel(name), dismiss, dirty = args.isNotEmpty(), dismissEnabled = !busy, actions = {
        PlayerButton(onClick = { submit(args) }, enabled = !busy && readyField(schema,args)) { Text("Submit") }
    }) {
        SchemaFields(schema,args) { draft = it.toString() }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

private fun fieldType(schema: JsonObject) = (schema["type"] as? JsonPrimitive)?.contentOrNull
private fun readyField(schema: JsonObject, value: JsonElement?): Boolean {
    if (value == null) return false
    schema["const"]?.let { return value == it }
    schema["enum"]?.jsonArray?.let { return value in it }
    (schema["anyOf"] ?: schema["oneOf"])?.jsonArray?.let { options -> return options.any { readyField(it.jsonObject,value) } }
    return when (fieldType(schema)) {
        "object" -> value is JsonObject && schema["required"]?.jsonArray.orEmpty().all {
            it.jsonPrimitive.content in value
        } && value.all { (key,v) -> schema["properties"]?.jsonObject?.get(key)?.jsonObject?.let { readyField(it,v) } == true }
        "array" -> value is JsonArray && value.size >= (schema["minItems"]?.jsonPrimitive?.intOrNull ?: 0) &&
            value.all { readyField(schema.getValue("items").jsonObject,it) }
        "null" -> value == JsonNull
        "boolean" -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
        "integer" -> value is JsonPrimitive && !value.isString && value.longOrNull?.let {
            it >= (schema["minimum"]?.jsonPrimitive?.longOrNull ?: Long.MIN_VALUE) &&
                it <= (schema["maximum"]?.jsonPrimitive?.longOrNull ?: Long.MAX_VALUE)
        } == true
        "string" -> value is JsonPrimitive && value.isString &&
            value.content.length >= (schema["minLength"]?.jsonPrimitive?.intOrNull ?: 0)
        else -> false
    }
}

@Composable private fun SchemaFields(schema: JsonObject, values: JsonObject, update: (JsonObject) -> Unit) {
    val required = schema["required"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }.toSet()
    schema["properties"]?.jsonObject?.forEach { (name, raw) -> key(name) {
        val field = raw.jsonObject
        fun set(value: JsonElement?) {
            val next = values.toMutableMap()
            if (value == null) next.remove(name) else next[name] = value
            update(JsonObject(next))
        }
        if (name in required || name in values) {
            SchemaField(cloudLabel(name), field, values[name], ::set)
            if (name !in required) PlayerTextButton({ set(null) }) { Text("Omit ${cloudLabel(name)}") }
        } else PlayerTextButton({ set(initialValue(field)) }) { Text("Add ${cloudLabel(name)}") }
    } }
}

private fun initialValue(schema: JsonObject): JsonElement = when (fieldType(schema)) {
    "object" -> buildJsonObject {}
    "array" -> JsonArray(emptyList())
    "string" -> JsonPrimitive("")
    else -> JsonNull
}

@Composable private fun SchemaField(label: String, schema: JsonObject, value: JsonElement?, onChange: (JsonElement?) -> Unit) {
    val options = (schema["anyOf"] ?: schema["oneOf"]) as? JsonArray
    val choices = schema["enum"] as? JsonArray
    if (options != null) {
        val variants = options.map { it.jsonObject }
        var selected by rememberSaveable(label) { mutableIntStateOf(variants.indexOfFirst { readyField(it,value) }.coerceAtLeast(0)) }
        val variant = variants[selected]
        Row { variants.forEachIndexed { i, item ->
            PlayerTextButton({ selected = i; onChange(initialValue(item)) }) { Text(cloudLabel(fieldType(item) ?: "Option ${i+1}")) }
        } }
        key(selected) { SchemaField(label,variant,value,onChange) }
    } else if (choices != null || schema.containsKey("const") || fieldType(schema) == "boolean") {
        val allowed = choices?.toList() ?: schema["const"]?.let(::listOf) ?: listOf(JsonPrimitive(true),JsonPrimitive(false))
        Text(label, style = MaterialTheme.typography.labelLarge)
        var show by remember { mutableStateOf(false) }
        Box {
            PlayerOutlinedButton({ show = true }) { Text((value as? JsonPrimitive)?.takeUnless { it == JsonNull }?.content ?: "Select") }
            DropdownMenu(show,{ show = false }) { allowed.forEach { choice ->
                DropdownMenuItem(text = { Text(cloudLabel(choice.jsonPrimitive.content)) }, onClick = { onChange(choice); show = false })
            } }
        }
    } else when (fieldType(schema)) {
        "object" -> PlayerDisclosure(label, initiallyOpen = true) {
            if (value !is JsonObject) PlayerTextButton({ onChange(buildJsonObject {}) }) { Text("Set $label") }
            SchemaFields(schema,value as? JsonObject ?: buildJsonObject {}) { onChange(it) }
        }
        "array" -> {
            Text(label, style=MaterialTheme.typography.labelLarge)
            val items = value as? JsonArray ?: JsonArray(emptyList())
            val item = schema.getValue("items").jsonObject
            items.forEachIndexed { i, old ->
                Column(Modifier.fillMaxWidth().padding(start=8.dp)) {
                    SchemaField("$label ${i+1}",item,old) { v -> onChange(JsonArray(items.toMutableList().apply { set(i,v ?: JsonNull) })) }
                    PlayerTextButton({ onChange(JsonArray(items.filterIndexed { index,_ -> index != i })) }) { Text("Remove ${i+1}") }
                }
            }
            PlayerTextButton({ onChange(JsonArray(items + initialValue(item))) },
                enabled = items.size < (schema["maxItems"]?.jsonPrimitive?.intOrNull ?: 256)) { Text("Add entry") }
            if (value == null) PlayerTextButton({ onChange(JsonArray(emptyList())) }) { Text("Use empty list") }
        }
        "null" -> PlayerTextButton({ onChange(JsonNull) }) { Text("$label · unknown") }
        "string", "integer" -> {
            val numeric = fieldType(schema) == "integer"
            val primitive = value as? JsonPrimitive
            var str by rememberSaveable(label) { mutableStateOf(primitive?.takeUnless { it == JsonNull }?.content.orEmpty()) }
            OutlinedTextField(value = str, onValueChange = {
                str = it
                onChange(if (numeric) it.toLongOrNull()?.let(::JsonPrimitive) else JsonPrimitive(it))
            }, label = { Text(label) }, modifier = Modifier.fillMaxWidth(),
                isError = str.isNotEmpty() && numeric && str.toLongOrNull() == null)
        }
    }
}
