package org.coralprotocol.coralserver.session

import io.kotest.assertions.ktor.client.shouldBeOK
import io.kotest.inspectors.forAllValues
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.maps.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.string.shouldNotBeBlank
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import org.coralprotocol.coralserver.CoralTest
import org.coralprotocol.coralserver.agent.debug.ECHO_AGENT_IDENTIFIER
import org.coralprotocol.coralserver.agent.debug.SEED_AGENT_IDENTIFIER
import org.coralprotocol.coralserver.dsl.sessionRequest
import org.coralprotocol.coralserver.routes.api.v1.LocalSessions
import org.koin.core.component.inject
import kotlin.time.Duration.Companion.seconds

/**
 * Focused coverage for the `EchoDebugAgent` change in commit 99cb6cb4 (coral-server issue #160): the agent's
 * reply now must be constructed as `listOf(SessionThreadMessagePart.Text(...))` instead of a bare `String`.
 *
 * `DebugAgentsTest.testEchoDebugAgent` already proves the echo agent produces the right *number* of replies,
 * but never inspects the *shape* of a reply's `content`. That leaves a real gap introduced by this exact
 * refactor: a build that regressed to an empty content list, the wrong `SessionThreadMessagePart` variant
 * (e.g. an image/resource-link part instead of text), or more than one part per reply would still satisfy
 * the existing count-only assertions. This test closes that gap without asserting on the literal reply
 * string ("nice message!" is an implementation-chosen debug fixture detail, not a documented contract) --
 * only on the documented contract that the echo agent "echoes messages" back wrapped in text content.
 */
class EchoDebugAgentContentTest : CoralTest({
    test("testEchoDebugAgentWrapsRepliesAsSingleTextPart").config(invocationTimeout = 30.seconds) {
        val client by inject<HttpClient>()
        val localSessionManager by inject<LocalSessionManager>()

        val threadCount = 1u
        val messageCount = 3u

        val sessionId: SessionIdentifier = client.authenticatedPost(LocalSessions.Session()) {
            setBody(sessionRequest {
                agentGraphRequest {
                    agent(SEED_AGENT_IDENTIFIER) {
                        unsignedIntOption("START_DELAY", 100u)
                        unsignedIntOption("OPERATION_DELAY", 50u)
                        unsignedIntOption("SEED_THREAD_COUNT", threadCount)
                        unsignedIntOption("SEED_MESSAGE_COUNT", messageCount)
                        stringListOption("PARTICIPANTS", "echo")
                        stringListOption("MENTIONS", "echo")
                    }
                    agent(ECHO_AGENT_IDENTIFIER) {
                        unsignedIntOption("ITERATION_COUNT", threadCount * messageCount)
                        stringOption("FROM_AGENT", "seed")
                        booleanOption("MENTIONS", true)
                    }
                    groupAllAgents()
                }
            })
        }.shouldBeOK().body()

        val session = localSessionManager.getSessions(sessionId.namespace).firstOrNull().shouldNotBeNull()
        session.joinAgents()

        session.threads.shouldHaveSize(threadCount.toInt())
        session.threads.forAllValues { thread ->
            thread.withMessageLock { messages ->
                val echoMessages = messages.filter { it.senderName == "echo" }

                // sanity: echo actually replied the expected number of times (shape of the scenario, not the
                // specific focus of this test -- DebugAgentsTest already covers this exhaustively)
                echoMessages.shouldHaveSize(messageCount.toInt())

                // the actual focus: every echoed reply must be wrapped as exactly one non-blank text part,
                // per the new SessionThreadMessagePart content model this commit introduced for send_message
                echoMessages.forEach { echoMessage ->
                    echoMessage.content.shouldHaveSize(1)

                    val part = echoMessage.content.single()
                    part.shouldBeInstanceOf<SessionThreadMessagePart.Text>()
                    part.text.shouldNotBeBlank()
                }
            }
        }
    }
})
