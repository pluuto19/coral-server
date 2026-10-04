@file:OptIn(ExperimentalTime::class)

package org.coralprotocol.coralserver.session

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.property.Arb
import io.kotest.property.arbitrary.orNull
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.ExperimentalTime

/**
 * Contract/schema tests for [SessionThreadMessagePart] and [ResourceContents] -- the typed content model
 * introduced by "refactor: support rich content in send_message" (coral-server issue #160).
 *
 * Oracle sources used to derive the required shape BEFORE this file's implementation was inspected line by
 * line:
 *  - Issue #160's literal text: "send_message should allow embedded and/or linked resources/artifacts as
 *    per A2A and ACP", linking A2A's FilePart and ACP's content spec.
 *  - The approved implementation plan (lexical-floating-marble.md), which resolves that into five content
 *    kinds -- Text(text); Image(mimeType, data) / Audio(mimeType, data), both base64, both fields required;
 *    EmbeddedResource wrapping a Text-or-Blob ResourceContents; ResourceLink(uri, name required;
 *    mimeType/title/description optional, default null) -- plus ResourceContents(Text(text, mimeType?) /
 *    Blob(blob, mimeType?)).
 *  - This repo's own pre-existing serialization convention in this exact file (`SessionThreadMessageFilter`)
 *    and CLAUDE.md: every sealed type crossing a boundary is `@Serializable` with
 *    `@JsonClassDiscriminator("type")`.
 *
 * Finding (recorded, not fixed): the shipped `EmbeddedResource`/`ResourceContents` shape diverges from the
 * plan's literal sketch. The plan wrote `EmbeddedResource(uri: String, contents: ResourceContents)` with
 * `uri` living directly on `EmbeddedResource`. The shipped type instead has `EmbeddedResource(resource:
 * ResourceContents)` with `uri` (required) and `mimeType` (optional) promoted onto `ResourceContents`
 * itself. This matches real MCP `TextResourceContents`/`BlobResourceContents` more closely than the plan's
 * own sketch did, but it is an uncommunicated deviation from the written, approved plan (the plan's "open
 * items" section flags the `data` field name as unverified, but never flags this `uri` placement). Tests
 * below pin the shape that actually shipped so a further silent change either direction is caught.
 */
private val wireJson = Json {
    // Mirrors the production Json bean (Main.kt) exactly, since that is what actually governs the wire
    // contract for every MCP/HTTP caller of this type.
    encodeDefaults = true
    explicitNulls = false
}

private val partSerializer = SessionThreadMessagePart.serializer()
private val partListSerializer = ListSerializer(partSerializer)
private val contentsSerializer = ResourceContents.serializer()

private fun roundTripParts(parts: List<SessionThreadMessagePart>): List<SessionThreadMessagePart> =
    wireJson.decodeFromString(partListSerializer, wireJson.encodeToString(partListSerializer, parts))

private fun roundTripContents(contents: ResourceContents): ResourceContents =
    wireJson.decodeFromString(contentsSerializer, wireJson.encodeToString(contentsSerializer, contents))

private fun discriminatorOf(part: SessionThreadMessagePart): String =
    wireJson.encodeToJsonElement(partSerializer, part).jsonObject.getValue("type").jsonPrimitive.content

private fun discriminatorOf(contents: ResourceContents): String =
    wireJson.encodeToJsonElement(contentsSerializer, contents).jsonObject.getValue("type").jsonPrimitive.content

private fun minimalMessage(content: List<SessionThreadMessagePart>) = SessionThreadMessage(
    threadId = "thread-1",
    content = content,
    senderName = "agent-1",
    mentionNames = emptySet(),
)

private val adversarialStrings = listOf(
    "",
    "a".repeat(20_000),
    "line1\nline2\ttabbed\rcarriage",
    "quotes \" and backslash \\ and braces {}[]",
    "unpaired-looking json: {\"type\":\"text\",\"text\":\"nested\"}",
    "null byte:\u0000end",
    "unicode: 测试 🎉 café",
)

class SessionThreadMessagePartContractTest : FunSpec({

    // ---------------------------------------------------------------------------------------------------
    // Section A: JSON round-trip preserves every field exactly, for all five content kinds and both
    // ResourceContents variants. Falsification target (a): a plausible wrong implementation drops a field,
    // swaps a default, or corrupts data during (de)serialization while still "looking" correct on the
    // happy path.
    // ---------------------------------------------------------------------------------------------------

    test("Text round-trips exactly for arbitrary text (property)") {
        checkAll(Arb.string(0, 200)) { text ->
            roundTripParts(listOf(SessionThreadMessagePart.Text(text))) shouldBe
                listOf(SessionThreadMessagePart.Text(text))
        }
    }

    test("Image round-trips exactly for arbitrary data/mimeType (property)") {
        checkAll(Arb.string(0, 200), Arb.string(1, 40)) { data, mimeType ->
            roundTripParts(listOf(SessionThreadMessagePart.Image(data = data, mimeType = mimeType))) shouldBe
                listOf(SessionThreadMessagePart.Image(data = data, mimeType = mimeType))
        }
    }

    test("Audio round-trips exactly for arbitrary data/mimeType (property)") {
        checkAll(Arb.string(0, 200), Arb.string(1, 40)) { data, mimeType ->
            roundTripParts(listOf(SessionThreadMessagePart.Audio(data = data, mimeType = mimeType))) shouldBe
                listOf(SessionThreadMessagePart.Audio(data = data, mimeType = mimeType))
        }
    }

    test("ResourceContents.Text round-trips exactly including its required uri (property)") {
        checkAll(Arb.string(0, 200), Arb.string(1, 100), Arb.string(1, 40).orNull(0.3)) { text, uri, mimeType ->
            val contents = ResourceContents.Text(text = text, uri = uri, mimeType = mimeType)
            roundTripContents(contents) shouldBe contents
        }
    }

    test("ResourceContents.Blob round-trips exactly including its required uri (property)") {
        checkAll(Arb.string(0, 200), Arb.string(1, 100), Arb.string(1, 40).orNull(0.3)) { blob, uri, mimeType ->
            val contents = ResourceContents.Blob(blob = blob, uri = uri, mimeType = mimeType)
            roundTripContents(contents) shouldBe contents
        }
    }

    test("EmbeddedResource wrapping ResourceContents.Text round-trips exactly (property)") {
        checkAll(Arb.string(0, 200), Arb.string(1, 100)) { text, uri ->
            val part = SessionThreadMessagePart.EmbeddedResource(ResourceContents.Text(text = text, uri = uri))
            roundTripParts(listOf(part)) shouldBe listOf(part)
        }
    }

    test("EmbeddedResource wrapping ResourceContents.Blob round-trips exactly (property)") {
        checkAll(Arb.string(0, 200), Arb.string(1, 100)) { blob, uri ->
            val part = SessionThreadMessagePart.EmbeddedResource(ResourceContents.Blob(blob = blob, uri = uri))
            roundTripParts(listOf(part)) shouldBe listOf(part)
        }
    }

    test("ResourceLink round-trips exactly with every optional field populated (property)") {
        checkAll(
            Arb.string(1, 80), Arb.string(1, 40), Arb.string(1, 30), Arb.string(1, 30), Arb.string(1, 80)
        ) { uri, name, mimeType, title, description ->
            val part = SessionThreadMessagePart.ResourceLink(
                uri = uri,
                name = name,
                mimeType = mimeType,
                title = title,
                description = description,
            )
            roundTripParts(listOf(part)) shouldBe listOf(part)
        }
    }

    test("ResourceLink round-trips exactly with every optional field left null (example)") {
        val part = SessionThreadMessagePart.ResourceLink(uri = "https://example.com/file.txt", name = "file.txt")
        roundTripParts(listOf(part)) shouldBe listOf(part)

        val encoded = wireJson.encodeToJsonElement(partSerializer, part).jsonObject
        // explicitNulls = false (matches production Json): absent optional fields must not appear at all,
        // not appear as JSON null -- a wrong impl could use encodeDefaults-only semantics and emit
        // "mimeType": null instead of omitting the key.
        encoded.containsKey("mimeType") shouldBe false
        encoded.containsKey("title") shouldBe false
        encoded.containsKey("description") shouldBe false
    }

    test("a heterogeneous content list round-trips every kind exactly and preserves order") {
        val parts = listOf(
            SessionThreadMessagePart.Text("hello"),
            SessionThreadMessagePart.Image(data = "aW1hZ2U=", mimeType = "image/png"),
            SessionThreadMessagePart.Audio(data = "YXVkaW8=", mimeType = "audio/mpeg"),
            SessionThreadMessagePart.EmbeddedResource(
                ResourceContents.Text(text = "embedded", uri = "coral://doc/1")
            ),
            SessionThreadMessagePart.EmbeddedResource(
                ResourceContents.Blob(blob = "Ymxvcg==", uri = "coral://doc/2", mimeType = "application/pdf")
            ),
            SessionThreadMessagePart.ResourceLink(uri = "https://example.com/x", name = "x"),
            SessionThreadMessagePart.Text("goodbye"),
        )

        roundTripParts(parts) shouldContainExactly parts
    }

    test("adversarial strings (empty, huge, control chars, json-like, nul byte, unicode) round-trip exactly") {
        for (s in adversarialStrings) {
            roundTripParts(listOf(SessionThreadMessagePart.Text(s))) shouldBe
                listOf(SessionThreadMessagePart.Text(s))

            val contents = ResourceContents.Blob(blob = s, uri = "coral://doc/adversarial")
            roundTripContents(contents) shouldBe contents
        }
    }

    test("a large (~1MB) blob payload round-trips without truncation") {
        val big = "x".repeat(1_000_000)
        val contents = ResourceContents.Blob(blob = big, uri = "coral://doc/big")
        val result = roundTripContents(contents)
        result shouldBe contents
        (result as ResourceContents.Blob).blob.length shouldBe 1_000_000
    }

    // ---------------------------------------------------------------------------------------------------
    // Section B: polymorphic discriminator contract. Falsification target (b): the discriminator is
    // inconsistent/colliding across kinds, or an unrecognized/missing discriminator or required field
    // crashes unpredictably instead of failing closed with a catchable, documented exception type.
    // ---------------------------------------------------------------------------------------------------

    test("every SessionThreadMessagePart kind encodes a non-blank 'type' discriminator") {
        val samples = listOf(
            SessionThreadMessagePart.Text("t"),
            SessionThreadMessagePart.Image(data = "d", mimeType = "m"),
            SessionThreadMessagePart.Audio(data = "d", mimeType = "m"),
            SessionThreadMessagePart.EmbeddedResource(ResourceContents.Text(text = "t", uri = "u")),
            SessionThreadMessagePart.ResourceLink(uri = "u", name = "n"),
        )
        for (part in samples) {
            discriminatorOf(part).isBlank() shouldBe false
        }
    }

    test("the five SessionThreadMessagePart discriminator values are pairwise distinct") {
        val samples = listOf(
            SessionThreadMessagePart.Text("t"),
            SessionThreadMessagePart.Image(data = "d", mimeType = "m"),
            SessionThreadMessagePart.Audio(data = "d", mimeType = "m"),
            SessionThreadMessagePart.EmbeddedResource(ResourceContents.Text(text = "t", uri = "u")),
            SessionThreadMessagePart.ResourceLink(uri = "u", name = "n"),
        )
        val discriminators = samples.map(::discriminatorOf)
        discriminators.distinct().size shouldBe 5
    }

    test("the two ResourceContents discriminator values are distinct from each other") {
        val textType = discriminatorOf(ResourceContents.Text(text = "t", uri = "u"))
        val blobType = discriminatorOf(ResourceContents.Blob(blob = "b", uri = "u"))
        textType shouldNotBe blobType
    }

    test("an unknown discriminator value for SessionThreadMessagePart fails with a SerializationException, not an uncontrolled crash") {
        shouldThrow<SerializationException> {
            wireJson.decodeFromString(partSerializer, """{"type":"video","data":"x"}""")
        }
    }

    test("an unknown discriminator value for ResourceContents fails with a SerializationException, not an uncontrolled crash") {
        shouldThrow<SerializationException> {
            wireJson.decodeFromString(contentsSerializer, """{"type":"video","uri":"u"}""")
        }
    }

    test("a missing 'type' discriminator entirely fails predictably") {
        shouldThrow<SerializationException> {
            wireJson.decodeFromString(partSerializer, """{"text":"hello"}""")
        }
    }

    test("Text payload missing its required 'text' field fails predictably") {
        val type = discriminatorOf(SessionThreadMessagePart.Text("x"))
        shouldThrow<SerializationException> {
            wireJson.decodeFromString(partSerializer, """{"type":"$type"}""")
        }
    }

    test("Image payload missing its required 'data' field fails predictably") {
        val type = discriminatorOf(SessionThreadMessagePart.Image(data = "x", mimeType = "m"))
        shouldThrow<SerializationException> {
            wireJson.decodeFromString(partSerializer, """{"type":"$type","mimeType":"image/png"}""")
        }
    }

    test("Audio payload missing its required 'mimeType' field fails predictably") {
        val type = discriminatorOf(SessionThreadMessagePart.Audio(data = "x", mimeType = "m"))
        shouldThrow<SerializationException> {
            wireJson.decodeFromString(partSerializer, """{"type":"$type","data":"YQ=="}""")
        }
    }

    test("EmbeddedResource payload missing its required 'resource' field fails predictably") {
        val type = discriminatorOf(
            SessionThreadMessagePart.EmbeddedResource(ResourceContents.Text(text = "t", uri = "u"))
        )
        shouldThrow<SerializationException> {
            wireJson.decodeFromString(partSerializer, """{"type":"$type"}""")
        }
    }

    test("ResourceLink payload missing its required 'name' field fails predictably") {
        val type = discriminatorOf(SessionThreadMessagePart.ResourceLink(uri = "u", name = "n"))
        shouldThrow<SerializationException> {
            wireJson.decodeFromString(partSerializer, """{"type":"$type","uri":"https://example.com"}""")
        }
    }

    test("ResourceContents.Text payload missing its required 'uri' field fails predictably") {
        // Pins the finding above: uri is REQUIRED on ResourceContents (not optional, and not carried by
        // EmbeddedResource the way the written plan originally sketched it).
        val type = discriminatorOf(ResourceContents.Text(text = "t", uri = "u"))
        shouldThrow<SerializationException> {
            wireJson.decodeFromString(contentsSerializer, """{"type":"$type","text":"hello"}""")
        }
    }

    test("ResourceContents.Blob payload missing its required 'uri' field fails predictably") {
        val type = discriminatorOf(ResourceContents.Blob(blob = "b", uri = "u"))
        shouldThrow<SerializationException> {
            wireJson.decodeFromString(contentsSerializer, """{"type":"$type","blob":"Yg=="}""")
        }
    }

    test("an unexpected extra field in the payload is rejected under the production Json configuration") {
        // Production Json (Main.kt) never sets ignoreUnknownKeys, so it defaults to false: any extra field
        // is a hard failure, not a silently-ignored one. This pins current strictness so a future relaxation
        // (accidental or not) shows up here instead of surprising a caller in production.
        val type = discriminatorOf(SessionThreadMessagePart.Text("t"))
        shouldThrow<SerializationException> {
            wireJson.decodeFromString(partSerializer, """{"type":"$type","text":"hello","bogusField":123}""")
        }
    }

    test("key order in the JSON object does not change the decoded value (order independence)") {
        val type = discriminatorOf(SessionThreadMessagePart.ResourceLink(uri = "u", name = "n"))
        val inOrder = """{"type":"$type","uri":"https://example.com","name":"file"}"""
        val reordered = """{"name":"file","uri":"https://example.com","type":"$type"}"""

        wireJson.decodeFromString(partSerializer, inOrder) shouldBe
            wireJson.decodeFromString(partSerializer, reordered)
    }

    test("decoding the same payload twice is deterministic") {
        val part = SessionThreadMessagePart.EmbeddedResource(
            ResourceContents.Blob(blob = "Ymxvcg==", uri = "coral://doc/3", mimeType = "application/pdf")
        )
        val encoded = wireJson.encodeToString(partSerializer, part)
        wireJson.decodeFromString(partSerializer, encoded) shouldBe
            wireJson.decodeFromString(partSerializer, encoded)
    }

    // ---------------------------------------------------------------------------------------------------
    // Section C: asJsonState()'s content -> messageText projection. Falsification target (c): the summary
    // either leaks non-text payload data into the lightweight state view, or silently drops legitimate text
    // when it's mixed with other content kinds (instead of extracting exactly the Text parts, in order).
    // ---------------------------------------------------------------------------------------------------

    test("a single Text part becomes messageText verbatim") {
        val message = minimalMessage(listOf(SessionThreadMessagePart.Text("hello world")))
        message.asJsonState()["messageText"]?.jsonPrimitive?.content shouldBe "hello world"
    }

    test("multiple Text parts are ALL included, joined by newline, in original order") {
        val message = minimalMessage(
            listOf(
                SessionThreadMessagePart.Text("first"),
                SessionThreadMessagePart.Text("second"),
                SessionThreadMessagePart.Text("third"),
            )
        )
        message.asJsonState()["messageText"]?.jsonPrimitive?.content shouldBe "first\nsecond\nthird"
    }

    test("zero Text parts (only non-text content) yields an empty messageText, not null or a crash") {
        val message = minimalMessage(
            listOf(
                SessionThreadMessagePart.Image(data = "aW1n", mimeType = "image/png"),
                SessionThreadMessagePart.Audio(data = "YXVk", mimeType = "audio/mpeg"),
                SessionThreadMessagePart.ResourceLink(uri = "https://example.com", name = "doc"),
            )
        )
        val state = message.asJsonState()
        state["messageText"].shouldNotBeNull()
        state["messageText"]?.jsonPrimitive?.content shouldBe ""
    }

    test("Text parts interleaved with non-text parts are still extracted, in order, skipping the rest") {
        val message = minimalMessage(
            listOf(
                SessionThreadMessagePart.Text("before"),
                SessionThreadMessagePart.Image(data = "aW1n", mimeType = "image/png"),
                SessionThreadMessagePart.Text("after"),
            )
        )
        message.asJsonState()["messageText"]?.jsonPrimitive?.content shouldBe "before\nafter"
    }

    test("image data does not leak into messageText") {
        val canary = "CANARY_IMAGE_BASE64_DOES_NOT_BELONG_IN_MESSAGE_TEXT"
        val message = minimalMessage(
            listOf(
                SessionThreadMessagePart.Text("visible text"),
                SessionThreadMessagePart.Image(data = canary, mimeType = "image/png"),
            )
        )
        val messageText = message.asJsonState()["messageText"]?.jsonPrimitive?.content.orEmpty()
        messageText shouldNotContain canary
        messageText shouldBe "visible text"
    }

    test("audio data does not leak into messageText") {
        val canary = "CANARY_AUDIO_BASE64_DOES_NOT_BELONG_IN_MESSAGE_TEXT"
        val message = minimalMessage(listOf(SessionThreadMessagePart.Audio(data = canary, mimeType = "audio/mpeg")))
        val messageText = message.asJsonState()["messageText"]?.jsonPrimitive?.content.orEmpty()
        messageText shouldNotContain canary
        messageText shouldBe ""
    }

    test("resource link fields do not leak into messageText") {
        val message = minimalMessage(
            listOf(
                SessionThreadMessagePart.ResourceLink(
                    uri = "https://example.com/CANARY_URI",
                    name = "CANARY_NAME",
                    description = "CANARY_DESCRIPTION",
                )
            )
        )
        val messageText = message.asJsonState()["messageText"]?.jsonPrimitive?.content.orEmpty()
        messageText shouldNotContain "CANARY_URI"
        messageText shouldNotContain "CANARY_NAME"
        messageText shouldNotContain "CANARY_DESCRIPTION"
        messageText shouldBe ""
    }

    test("embedded textual resource content does not leak into messageText (only literal Text parts qualify)") {
        // Per the plan's explicit, deliberate simplification: only SessionThreadMessagePart.Text is
        // summarized. An EmbeddedResource wrapping ResourceContents.Text is a different, textual-but-not-
        // "Text" kind, and a plausible wrong implementation might greedily pull in any text-bearing content.
        val message = minimalMessage(
            listOf(
                SessionThreadMessagePart.Text("plain text"),
                SessionThreadMessagePart.EmbeddedResource(
                    ResourceContents.Text(text = "CANARY_EMBEDDED_TEXT", uri = "coral://doc/1")
                ),
            )
        )
        val messageText = message.asJsonState()["messageText"]?.jsonPrimitive?.content.orEmpty()
        messageText shouldNotContain "CANARY_EMBEDDED_TEXT"
        messageText shouldBe "plain text"
    }

    test("an empty content list yields an empty messageText and a well-formed state object") {
        val message = minimalMessage(emptyList())
        val state = message.asJsonState()
        state["messageText"]?.jsonPrimitive?.content shouldBe ""
        state["sendingAgentName"]?.jsonPrimitive?.content shouldBe "agent-1"
        state.containsKey("mentionAgentNames") shouldBe false
    }

    // ---------------------------------------------------------------------------------------------------
    // Section D: field-shape pinning and documented gaps.
    // ---------------------------------------------------------------------------------------------------

    test("EmbeddedResource's own JSON object carries no top-level 'uri' or 'contents' key") {
        // Pins the deviation from the written plan recorded above: 'uri' lives on the nested
        // ResourceContents, not on EmbeddedResource itself, and the wrapping field is named 'resource', not
        // 'contents'.
        val part = SessionThreadMessagePart.EmbeddedResource(
            ResourceContents.Text(text = "t", uri = "coral://doc/1")
        )
        val encoded = wireJson.encodeToJsonElement(partSerializer, part).jsonObject
        encoded.containsKey("uri") shouldBe false
        encoded.containsKey("contents") shouldBe false
        encoded.containsKey("resource") shouldBe true
        encoded["resource"]?.jsonObject?.containsKey("uri") shouldBe true
    }

    test("a non-validated, hostile-looking URI is accepted and round-trips unchanged (no URI format validation exists)") {
        val hostileUris = listOf(
            "not a uri at all",
            "javascript:alert(1)",
            "file:///etc/passwd",
            "../../../../etc/passwd",
        )
        for (uri in hostileUris) {
            val part = SessionThreadMessagePart.ResourceLink(uri = uri, name = "n")
            roundTripParts(listOf(part)) shouldBe listOf(part)
        }
    }
})
