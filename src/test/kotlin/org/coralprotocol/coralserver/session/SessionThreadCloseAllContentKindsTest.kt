package org.coralprotocol.coralserver.session

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import kotlinx.coroutines.cancel
import org.coralprotocol.coralserver.CoralTest
import org.coralprotocol.coralserver.agent.graph.AgentGraph
import org.coralprotocol.coralserver.dsl.graphAgentPair
import org.koin.test.inject

/**
 * [SessionTest.testMessages] already establishes the contract that closing a thread deletes its messages, but
 * every message sent anywhere in [SessionTest] (and every other test in this package) uses only
 * [SessionThreadMessagePart.Text]. [SessionThread.close] is documented to delete "all messages in the thread" with
 * no carve-out by content kind, and the sealed [SessionThreadMessagePart] hierarchy is designed so every variant is
 * interchangeable wherever the interface is expected (see the rich-content plan's OOP/Liskov rationale).
 *
 * This test re-exercises that same close-then-assert-empty contract, but with one message per non-[Text][SessionThreadMessagePart.Text]
 * content kind, plus a message mixing several kinds together. It would fail against a plausible wrong
 * implementation that accidentally scopes deletion to text content only -- for example by reusing the
 * `content.filterIsInstance<SessionThreadMessagePart.Text>()` pattern that [SessionThreadMessage.asJsonState] uses
 * (for an unrelated, deliberately text-only summary view) in the deletion path instead of an unconditional clear.
 * Such a mutant would still pass every existing test in this package, since none of them ever send non-text
 * content.
 */
class SessionThreadCloseAllContentKindsTest : CoralTest({
    test("testCloseDiscardsAllContentKinds") {
        val localSessionManager by inject<LocalSessionManager>()

        val session = localSessionManager.createSession(
            "ns",
            AgentGraph(
                agents = mapOf(
                    graphAgentPair("agent1"),
                    graphAgentPair("agent2"),
                )
            )
        ).first

        val agent1 = shouldNotThrowAny { session.getAgent("agent1") }
        val agent2 = shouldNotThrowAny { session.getAgent("agent2") }

        val thread = shouldNotThrowAny {
            session.createThread("Test thread", agent1.name, setOf(agent2.name))
        }

        shouldNotThrowAny {
            agent1.sendMessage(
                listOf(SessionThreadMessagePart.Image(data = "aGVsbG8=", mimeType = "image/png")),
                thread.id
            )

            agent1.sendMessage(
                listOf(SessionThreadMessagePart.Audio(data = "aGVsbG8=", mimeType = "audio/mpeg")),
                thread.id
            )

            agent1.sendMessage(
                listOf(
                    SessionThreadMessagePart.EmbeddedResource(
                        resource = ResourceContents.Text(text = "embedded text", uri = "coral://resource/1")
                    )
                ),
                thread.id
            )

            agent1.sendMessage(
                listOf(
                    SessionThreadMessagePart.EmbeddedResource(
                        resource = ResourceContents.Blob(blob = "aGVsbG8=", uri = "coral://resource/2")
                    )
                ),
                thread.id
            )

            agent1.sendMessage(
                listOf(
                    SessionThreadMessagePart.ResourceLink(
                        uri = "coral://resource/3",
                        name = "a linked resource"
                    )
                ),
                thread.id
            )

            // a single message mixing a text part together with a non-text part, so a check that only looks at the
            // first/only content part cannot accidentally pass
            agent1.sendMessage(
                listOf(
                    SessionThreadMessagePart.Text("mixed"),
                    SessionThreadMessagePart.Image(data = "aGVsbG8=", mimeType = "image/png"),
                ),
                thread.id
            )
        }

        agent1.getVisibleMessages().shouldHaveSize(6)
        agent2.getVisibleMessages().shouldHaveSize(6)

        thread.close(agent1, "closing with rich content still pending")

        // closing a thread should delete the messages, regardless of what content kinds they carry
        agent1.getVisibleMessages().shouldBeEmpty()
        agent2.getVisibleMessages().shouldBeEmpty()

        session.sessionScope.cancel()
    }
})
