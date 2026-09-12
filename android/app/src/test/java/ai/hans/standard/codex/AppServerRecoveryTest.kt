package ai.hans.standard.codex

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AppServerRecoveryTest {
    @Test
    fun logoutInterruptResumeAndThreadListUseCorrelatedPinnedShapes() {
        val correlator = ResponseCorrelator()

        val logout = AppServerRequests.accountLogout(RequestId.Number(1))
        assertFalse(JSONObject(logout.json).has("params"))
        correlator.register(logout)
        assertEquals(
            AccountLogoutResult,
            (correlator.accept("""{"id":1,"result":{}}""") as CorrelatedResponse.Success).result,
        )

        val resume = AppServerRequests.threadResume(
            RequestId.Number(2),
            threadId = "thread-recovery",
        )
        val resumeParams = JSONObject(resume.json).getJSONObject("params")
        assertTrue(resumeParams.getBoolean("excludeTurns"))
        val initialTurnsRequest = resumeParams.getJSONObject("initialTurnsPage")
        assertEquals(
            ProtocolLimits.RECENT_HISTORY_TURN_LIMIT,
            initialTurnsRequest.getInt("limit"),
        )
        assertEquals("desc", initialTurnsRequest.getString("sortDirection"))
        assertEquals("summary", initialTurnsRequest.getString("itemsView"))
        correlator.register(resume)
        val resumed = correlator.accept(
            JSONObject()
                .put("id", 2)
                .put(
                    "result",
                    JSONObject()
                        .put("thread", fullThread("thread-recovery"))
                        .put("model", "gpt-5.6-luna")
                        .put("modelProvider", "openai")
                        .put("reasoningEffort", "max")
                        .put("serviceTier", JSONObject.NULL)
                        .put("approvalPolicy", "never")
                        .put("approvalsReviewer", "user")
                        .put("cwd", "/data/user/0/ai.hans.standard/files")
                        .put("sandbox", JSONObject().put("type", "dangerFullAccess"))
                        .put(
                            "initialTurnsPage",
                            JSONObject().put(
                                "data",
                                JSONArray()
                                    .put(
                                        JSONObject()
                                            .put("id", "turn-complete")
                                            .put("status", "completed")
                                            .put(
                                                "items",
                                                JSONArray()
                                                    .put(
                                                        JSONObject()
                                                            .put("type", "userMessage")
                                                            .put("id", "user-complete")
                                                            .put("clientId", "client-complete")
                                                            .put(
                                                                "content",
                                                                JSONArray()
                                                                    .put(
                                                                        JSONObject()
                                                                            .put("type", "text")
                                                                            .put("text", "Visible request"),
                                                                    )
                                                                    .put(
                                                                        JSONObject()
                                                                            .put("type", "localImage")
                                                                            .put("path", "/private/not-projected.jpg"),
                                                                    ),
                                                            ),
                                                    )
                                                    .put(
                                                        JSONObject()
                                                            .put("type", "agentMessage")
                                                            .put("id", "agent-complete")
                                                            .put("text", "**Recovered** answer")
                                                            .put("phase", "final_answer"),
                                                    ),
                                            )
                                            .put("itemsView", "summary"),
                                    )
                                    .put(
                                        JSONObject()
                                            .put("id", "turn-active")
                                            .put("status", "inProgress")
                                            .put("items", JSONArray())
                                            .put("itemsView", "summary"),
                                    ),
                            ),
                        )
                        .put("turnsBackwardsCursor", "turn-page")
                        .put("itemsBackwardsCursor", JSONObject.NULL),
                )
                .toString(),
        ) as CorrelatedResponse.Success
        val resumeResult = resumed.result as ThreadResumeResult
        assertEquals("thread-recovery", resumeResult.thread.id)
        assertEquals(ReasoningEffort.MAX, resumeResult.effectiveEffort)
        assertEquals(
            listOf(
                ThreadTurnReceipt("turn-complete", TurnStatus.COMPLETED),
                ThreadTurnReceipt("turn-active", TurnStatus.IN_PROGRESS),
            ),
            resumeResult.initialTurnReceipts,
        )
        assertEquals(
            listOf("turn-complete", "turn-complete"),
            resumeResult.recoveredItems.map { it.turnId },
        )
        val recoveredUser = resumeResult.recoveredItems[0] as RecoveredConversationItem.User
        assertEquals("client-complete", recoveredUser.clientId)
        assertEquals(listOf("Visible request"), recoveredUser.textParts)
        assertTrue(recoveredUser.hasAttachment)
        val recoveredHans = resumeResult.recoveredItems[1] as RecoveredConversationItem.Hans
        assertEquals("**Recovered** answer", recoveredHans.text)
        assertEquals(RecoveredHistoryStatus.LOADED, resumeResult.recoveredHistoryStatus)
        assertEquals("turn-page", resumeResult.turnsBackwardsCursor)

        val list = AppServerRequests.threadList(RequestId.Number(3), limit = 20)
        val listParams = JSONObject(list.json).getJSONObject("params")
        assertEquals("updated_at", listParams.getString("sortKey"))
        assertEquals("desc", listParams.getString("sortDirection"))
        correlator.register(list)
        val listed = correlator.accept(
            JSONObject()
                .put("id", 3)
                .put(
                    "result",
                    JSONObject()
                        .put("data", JSONArray().put(fullThread("thread-recovery")))
                        .put("nextCursor", JSONObject.NULL)
                        .put("backwardsCursor", "newer"),
                )
                .toString(),
        ) as CorrelatedResponse.Success
        val listResult = listed.result as ThreadListResult
        assertEquals(listOf("thread-recovery"), listResult.threads.map { it.id })
        assertNull(listResult.nextCursor)
        assertEquals("newer", listResult.backwardsCursor)

        val interrupt = AppServerRequests.turnInterrupt(
            RequestId.Number(4),
            threadId = "thread-recovery",
            turnId = "turn-live",
        )
        correlator.register(interrupt)
        val interrupted = correlator.accept("""{"id":4,"result":{}}""")
            as CorrelatedResponse.Success
        assertEquals(
            TurnInterruptResult("thread-recovery", "turn-live"),
            interrupted.result,
        )
    }

    @Test
    fun optionalResumeHistoryFailsClosedWithoutInvalidatingTheResumedThread() {
        val invalidPages = listOf(
            // Missing required turn fields.
            JSONObject().put(
                "data",
                JSONArray().put(JSONObject().put("id", "malformed-turn")),
            ),
            // Model-level opaque-id invariants use IllegalArgumentException, not ProtocolException.
            JSONObject().put(
                "data",
                JSONArray().put(
                    summaryTurn(
                        id = "invalid-id-turn",
                        items = JSONArray().put(
                            JSONObject()
                                .put("type", "agentMessage")
                                .put("id", "bad\u0000id")
                                .put("text", "Must be discarded"),
                        ),
                    ),
                ),
            ),
            // Summary projection must never contain tool data.
            JSONObject().put(
                "data",
                JSONArray().put(
                    summaryTurn(
                        id = "tool-turn",
                        items = JSONArray().put(
                            JSONObject()
                                .put("type", "commandExecution")
                                .put("id", "tool-item")
                                .put("aggregatedOutput", "must not cross into history"),
                        ),
                    ),
                ),
            ),
            // Per-message bound is enforced transactionally rather than truncating content.
            JSONObject().put(
                "data",
                JSONArray().put(
                    summaryTurn(
                        id = "large-text-turn",
                        items = JSONArray().put(
                            JSONObject()
                                .put("type", "agentMessage")
                                .put("id", "large-agent")
                                .put(
                                    "text",
                                    "x".repeat(
                                        ProtocolLimits.MAX_RECOVERED_HISTORY_MESSAGE_BYTES + 1,
                                    ),
                                ),
                        ),
                    ),
                ),
            ),
        )

        invalidPages.forEachIndexed { index, page ->
            val correlator = ResponseCorrelator()
            val requestId = 40L + index
            correlator.register(
                AppServerRequests.threadResume(
                    RequestId.Number(requestId),
                    "thread-still-resumed",
                ),
            )

            val response = correlator.accept(
                resumeResponse(
                    requestId = requestId,
                    threadId = "thread-still-resumed",
                    initialTurnsPage = page,
                ).toString(),
            ) as CorrelatedResponse.Success
            val result = response.result as ThreadResumeResult

            assertEquals("thread-still-resumed", result.thread.id)
            assertEquals(RecoveredHistoryStatus.UNAVAILABLE, result.recoveredHistoryStatus)
            assertTrue(result.initialTurnReceipts.isEmpty())
            assertTrue(result.recoveredItems.isEmpty())
            assertEquals(0, correlator.pendingCount())
        }
    }

    @Test
    fun optionalResumeHistoryIsBoundToExactlyTheRequestedTwentyFiveTurns() {
        val requestedLimit = ProtocolLimits.RECENT_HISTORY_TURN_LIMIT
        val exactPage = JSONObject().put(
            "data",
            JSONArray().also { turns ->
                repeat(requestedLimit) { index ->
                    turns.put(summaryTurn("turn-$index"))
                }
            },
        )
        val exactResult = acceptResume(
            requestId = 60,
            threadId = "thread-exact-page",
            initialTurnsPage = exactPage,
        )
        assertEquals(RecoveredHistoryStatus.LOADED, exactResult.recoveredHistoryStatus)
        assertEquals(requestedLimit, exactResult.initialTurnReceipts.size)

        val oversizedPage = JSONObject().put(
            "data",
            JSONArray().also { turns ->
                repeat(requestedLimit + 1) { index ->
                    turns.put(summaryTurn("turn-$index"))
                }
            },
        )
        val oversizedResult = acceptResume(
            requestId = 61,
            threadId = "thread-oversized-page",
            initialTurnsPage = oversizedPage,
        )
        assertEquals(RecoveredHistoryStatus.UNAVAILABLE, oversizedResult.recoveredHistoryStatus)
        assertTrue(oversizedResult.initialTurnReceipts.isEmpty())
        assertTrue(oversizedResult.recoveredItems.isEmpty())

        val combinedBudgetPage = JSONObject().put(
            "data",
            JSONArray().also { turns ->
                repeat(requestedLimit) { index ->
                    val text = "x".repeat(11 * 1024)
                    turns.put(
                        summaryTurn(
                            id = "budget-turn-$index",
                            items = JSONArray()
                                .put(
                                    JSONObject()
                                        .put("type", "userMessage")
                                        .put("id", "budget-user-$index")
                                        .put(
                                            "content",
                                            JSONArray().put(
                                                JSONObject()
                                                    .put("type", "text")
                                                    .put("text", text),
                                            ),
                                        ),
                                )
                                .put(
                                    JSONObject()
                                        .put("type", "agentMessage")
                                        .put("id", "budget-agent-$index")
                                        .put("text", text),
                                ),
                        ),
                    )
                }
            },
        )
        val combinedBudgetResult = acceptResume(
            requestId = 62,
            threadId = "thread-combined-budget",
            initialTurnsPage = combinedBudgetPage,
        )
        assertEquals(
            RecoveredHistoryStatus.UNAVAILABLE,
            combinedBudgetResult.recoveredHistoryStatus,
        )
        assertTrue(combinedBudgetResult.initialTurnReceipts.isEmpty())
        assertTrue(combinedBudgetResult.recoveredItems.isEmpty())
    }

    @Test
    fun absentOptionalResumeHistoryDoesNotInvalidateTheResumedThread() {
        val correlator = ResponseCorrelator()
        val request = AppServerRequests.threadResume(
            RequestId.Number(70),
            "thread-without-page",
        )
        correlator.register(request)
        val response = resumeResponse(
            requestId = 70,
            threadId = "thread-without-page",
            initialTurnsPage = null,
        )
        val result = (
            correlator.accept(response.toString()) as CorrelatedResponse.Success
            ).result as ThreadResumeResult

        assertEquals("thread-without-page", result.thread.id)
        assertEquals(RecoveredHistoryStatus.UNAVAILABLE, result.recoveredHistoryStatus)
        assertTrue(result.initialTurnReceipts.isEmpty())
        assertTrue(result.recoveredItems.isEmpty())
    }

    @Test
    fun resumeCannotCrossCorrelateAThread() {
        val correlator = ResponseCorrelator()
        correlator.register(
            AppServerRequests.threadResume(RequestId.Number(10), "thread-requested"),
        )

        assertThrows(CrossCorrelationException::class.java) {
            correlator.accept(
                JSONObject()
                    .put("id", 10)
                    .put(
                        "result",
                        JSONObject()
                            .put("thread", fullThread("thread-other"))
                            .put("model", "gpt-5.6-luna"),
                    )
                    .toString(),
            )
        }
        assertEquals(1, correlator.pendingCount())
    }

    private fun acceptResume(
        requestId: Long,
        threadId: String,
        initialTurnsPage: JSONObject,
    ): ThreadResumeResult {
        val correlator = ResponseCorrelator()
        correlator.register(
            AppServerRequests.threadResume(RequestId.Number(requestId), threadId),
        )
        return (
            correlator.accept(
                resumeResponse(requestId, threadId, initialTurnsPage).toString(),
            ) as CorrelatedResponse.Success
            ).result as ThreadResumeResult
    }

    private fun resumeResponse(
        requestId: Long,
        threadId: String,
        initialTurnsPage: JSONObject?,
    ): JSONObject = JSONObject()
        .put("id", requestId)
        .put(
            "result",
            JSONObject()
                .put("thread", fullThread(threadId))
                .put("model", "gpt-5.6-luna")
                .put("reasoningEffort", "max")
                .put("serviceTier", JSONObject.NULL)
                .put("initialTurnsPage", initialTurnsPage ?: JSONObject.NULL)
                .put("turnsBackwardsCursor", JSONObject.NULL)
                .put("itemsBackwardsCursor", JSONObject.NULL),
        )

    private fun summaryTurn(
        id: String,
        items: JSONArray = JSONArray(),
    ): JSONObject = JSONObject()
        .put("id", id)
        .put("status", "completed")
        .put("itemsView", "summary")
        .put("items", items)

    @Test
    fun modelPaginationMergesExactPagesAndRejectsLoopsOrConflicts() {
        val first = parseModelPage(
            requestId = 20,
            requestedCursor = null,
            model = modelJson("gpt-5.6-luna", "max"),
            nextCursor = "page-2",
        )
        val second = parseModelPage(
            requestId = 21,
            requestedCursor = "page-2",
            model = modelJson("gpt-5.6-sol", "ultra"),
            nextCursor = null,
        )
        val accumulator = ModelListAccumulator()
        assertEquals("page-2", accumulator.append(first).models.single().let { first.nextCursor })
        val merged = accumulator.append(second)
        assertEquals(listOf("gpt-5.6-luna", "gpt-5.6-sol"), merged.models.map { it.wireModel })
        assertTrue(accumulator.isComplete())

        assertThrows(CrossCorrelationException::class.java) {
            accumulator.append(second)
        }

        val conflictAccumulator = ModelListAccumulator()
        conflictAccumulator.append(first)
        val conflict = parseModelPage(
            requestId = 22,
            requestedCursor = "page-2",
            model = modelJson("gpt-5.6-luna", "medium"),
            nextCursor = null,
        )
        assertThrows(CrossCorrelationException::class.java) {
            conflictAccumulator.append(conflict)
        }

        val loopAccumulator = ModelListAccumulator()
        loopAccumulator.append(first)
        val loop = parseModelPage(
            requestId = 23,
            requestedCursor = "page-2",
            model = modelJson("gpt-5.6-sol", "ultra"),
            nextCursor = "page-2",
        )
        assertThrows(CrossCorrelationException::class.java) {
            loopAccumulator.append(loop)
        }
    }

    @Test
    fun stableSkillsListIsTypedAndBounded() {
        val correlator = ResponseCorrelator()
        val request = AppServerRequests.skillsList(
            id = RequestId.Number(30),
            workingDirectories = listOf("/data/user/0/ai.hans.standard/files"),
            forceReload = true,
        )
        correlator.register(request)
        val response = correlator.accept(
            """
            {
              "id":30,
              "result":{"data":[{
                "cwd":"/data/user/0/ai.hans.standard/files",
                "skills":[{
                  "name":"phone",
                  "path":"/data/user/0/ai.hans.standard/files/skills/phone/SKILL.md",
                  "description":"Public Android phone actions",
                  "enabled":true,
                  "scope":"user",
                  "dependencies":null,
                  "interface":{"displayName":"Telefon","shortDescription":"Telefon bedienen","iconSmall":null,"iconSmallUrl":null}
                }],
                "errors":[]
              }]}
            }
            """.trimIndent(),
        ) as CorrelatedResponse.Success
        val result = response.result as SkillsListResult

        assertEquals("Telefon", result.roots.single().skills.single().displayName)
        assertEquals(SkillScope.USER, result.roots.single().skills.single().scope)
    }

    private fun parseModelPage(
        requestId: Long,
        requestedCursor: String?,
        model: JSONObject,
        nextCursor: String?,
    ): ModelListResult {
        val correlator = ResponseCorrelator()
        correlator.register(
            AppServerRequests.modelList(RequestId.Number(requestId), requestedCursor),
        )
        val response = correlator.accept(
            JSONObject()
                .put("id", requestId)
                .put(
                    "result",
                    JSONObject()
                        .put("data", JSONArray().put(model))
                        .put("nextCursor", nextCursor ?: JSONObject.NULL),
                )
                .toString(),
        ) as CorrelatedResponse.Success
        return response.result as ModelListResult
    }

    private fun modelJson(id: String, effort: String): JSONObject = JSONObject()
        .put("id", id)
        .put("model", id)
        .put("displayName", id)
        .put("description", "Model")
        .put("hidden", false)
        .put("isDefault", id.endsWith("luna"))
        .put("defaultReasoningEffort", effort)
        .put(
            "supportedReasoningEfforts",
            JSONArray().put(
                JSONObject()
                    .put("reasoningEffort", effort)
                    .put("description", effort),
            ),
        )
        .put("defaultServiceTier", JSONObject.NULL)
        .put("serviceTiers", JSONArray())

    private fun fullThread(id: String): JSONObject = JSONObject()
        .put("id", id)
        .put("name", "Hans")
        .put("preview", "Hallo")
        .put("createdAt", 100L)
        .put("updatedAt", 200L)
        .put("status", JSONObject().put("type", "idle"))
        .put("cliVersion", "0.154.0")
        .put("cwd", "/data/user/0/ai.hans.standard/files")
        .put("ephemeral", false)
        .put("modelProvider", "openai")
        .put("projectId", JSONObject.NULL)
        .put("sessionId", "session-1")
        .put("source", "appServer")
        .put("turns", JSONArray())
}
