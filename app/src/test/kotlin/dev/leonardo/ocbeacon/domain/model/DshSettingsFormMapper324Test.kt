package dev.leonardo.ocbeacon.domain.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #324③ settings/describe → 动态表单映射测试（schema 驱动轻量表单）。
 *
 * 契约锚点：dsh-api-settings-controller typert——describe 回程
 * {writable,hasDocument,namespaces:[{ns,schema,value,base?,user?,applies,secrets:[{path,set}],revision}]}；
 * schema 为 rehydrate 形态（refs/union/const——permission 档位先例 parseSchemaEnumOptions）；
 * secret 角色字段被 redactSecrets 剥离（value 缺席 + secrets 列表披露位置与 set 态）。
 * 绑定规则（web mod41 同构）：secret 的凭据 ref = 同 ns 顶层 *Env 字符串字段值。
 */
class DshSettingsFormMapper324Test {

    private fun ns(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test
    fun `maps scalar fields by json type and enum options from schema`() {
        val entry = ns("""{
            "ns":"agent-loop",
            "schema":{},
            "value":{"maxParallelToolCalls":10,"strictFlag":true,"label":"loose"},
            "applies":"live",
            "secrets":[],
            "revision":4
        }""")
        val form = DshSettingsFormMapper.map(entry, nsWritable = true)!!
        assertEquals("agent-loop", form.ns)
        assertEquals(4L, form.revision)
        assertTrue(form.writable)
        assertEquals("live", form.applies)
        val byKey = form.fields.associateBy { it.key }
        assertEquals(DshSettingsFieldKind.NUMBER, byKey["maxParallelToolCalls"]!!.kind)
        assertEquals(JsonPrimitive(10), byKey["maxParallelToolCalls"]!!.value)
        assertEquals(DshSettingsFieldKind.BOOLEAN, byKey["strictFlag"]!!.kind)
        assertEquals(DshSettingsFieldKind.TEXT, byKey["label"]!!.kind)
    }

    @Test
    fun `enum options come from union of const refs schema`() {
        val entry = ns("""{
            "ns":"permission",
            "schema":{"uid":"1","refs":{
                "10":{"type":"const","value":"ask"},
                "11":{"type":"const","value":"always"},
                "12":{"type":"const","value":"never"},
                "99":{"type":"union","list":["10","11","12"]}
            }},
            "value":{"defaultPreset":"ask"},
            "applies":"live",
            "secrets":[],
            "revision":2
        }""")
        val form = DshSettingsFormMapper.map(entry, true)!!
        val field = form.fields.single()
        assertEquals(DshSettingsFieldKind.ENUM, field.kind)
        assertEquals(listOf("ask", "always", "never"), field.options)
        assertEquals("ask", (field.value as JsonPrimitive).content)
    }

    @Test
    fun `secret field binds credential ref to sibling env field and reports set state`() {
        val entry = ns("""{
            "ns":"web-search-deepseek",
            "schema":{},
            "value":{"apiKeyEnv":"DEEPSEEK_SEARCH_API_KEY","baseURL":"https://x","maxUses":5},
            "applies":"restart",
            "secrets":[{"path":["apiKey"],"set":true}],
            "revision":7
        }""")
        val form = DshSettingsFormMapper.map(entry, true)!!
        val byKey = form.fields.associateBy { it.key }
        // secret 字段：value 剥离（null）+ set 态 + ref 解析到 apiKeyEnv 值
        val secret = byKey["apiKey"]!!
        assertEquals(DshSettingsFieldKind.SECRET, secret.kind)
        assertNull(secret.value)
        assertEquals(true, secret.secretSet)
        assertEquals("DEEPSEEK_SEARCH_API_KEY", secret.secretRef)
        // Env 字段本身仍渲染（TEXT）；ref 可改写（换 key 存放位）
        assertEquals(DshSettingsFieldKind.TEXT, byKey["apiKeyEnv"]!!.kind)
    }

    @Test
    fun `secret without env sibling yields unresolvable ref`() {
        val entry = ns("""{
            "ns":"odd",
            "schema":{},
            "value":{"only":"plain"},
            "applies":"live",
            "secrets":[{"path":["key"],"set":false}],
            "revision":1
        }""")
        val form = DshSettingsFormMapper.map(entry, true)!!
        val secret = form.fields.single { it.key == "key" }
        assertEquals(false, secret.secretSet)
        assertNull(secret.secretRef)
    }

    @Test
    fun `nested secret paths and non scalar values are skipped`() {
        val entry = ns("""{
            "ns":"x",
            "schema":{},
            "value":{"deep":{"a":1},"list":[1,2],"ok":true},
            "applies":"live",
            "secrets":[{"path":["deep","a"],"set":true}],
            "revision":1
        }""")
        val form = DshSettingsFormMapper.map(entry, true)!!
        // 非标量顶层值（对象/数组）不进轻量表单；嵌套 secret 不生成字段
        assertEquals(listOf("ok"), form.fields.map { it.key })
    }

    @Test
    fun `null form on missing ns`() {
        assertNull(DshSettingsFormMapper.map(ns("""{"value":{}}"""), true))
    }

    @Test
    fun `not writable passthrough`() {
        val entry = ns("""{"ns":"ro","schema":{},"value":{"k":"v"},"applies":"live","secrets":[],"revision":1}""")
        val form = DshSettingsFormMapper.map(entry, false)!!
        assertFalse(form.writable)
    }
}
