package ai.hans.standard.notifications

import ai.hans.standard.phone.consent.AtomicPersistentAndroidConsentStore
import ai.hans.standard.phone.consent.PersistentAndroidConsentDescriptor
import ai.hans.standard.phone.consent.PersistentAndroidConsentScope
import ai.hans.standard.phone.notifications.NotificationEventKind
import ai.hans.standard.phone.notifications.NotificationInboxEvent
import ai.hans.standard.phone.notifications.NotificationSnapshot
import ai.hans.standard.phone.publicapi.CalendarInstance
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootFreeNotificationEnrichmentTest {
    @Test
    fun validPublicHttpsFetchReturnsOnlyBoundedMetadataAndPinnedDns() {
        val lookups = AtomicInteger(0)
        val pinned = mutableListOf<List<InetAddress>>()
        val source = SafeHttpsNotificationMetadataSource(
            resolver = NotificationDnsResolver { _, _, _ ->
                lookups.incrementAndGet()
                listOf(ip("8.8.8.8"))
            },
            transport = PinnedHttpsMetadataTransport { endpoint, _, _, _ ->
                pinned += endpoint.pinnedAddresses
                html(
                    """
                    <html><head>
                    <title>Fallback title</title>
                    <meta property="og:title" content="Konzert &amp; Freunde">
                    <meta name="description" content="Freitag um 20 Uhr">
                    <script>throw new Error('must never execute')</script>
                    </head></html>
                    """.trimIndent(),
                )
            },
        )

        val result = source.read(envelope("https://events.example.net/item"))
            as ReadOnlyEnrichmentResult.Available

        assertEquals(1, lookups.get())
        assertEquals(listOf(ip("8.8.8.8")), pinned.single())
        assertEquals("https://events.example.net", result.value.single().finalUrlOrigin)
        assertEquals("Konzert & Freunde", result.value.single().title)
        assertEquals("Freitag um 20 Uhr", result.value.single().description)
        assertFalse(result.value.single().toString().contains("tracking"))
    }

    @Test
    fun localPrivateCgnatLinkLocalAndReservedIpv4AreRejectedBeforeTransport() {
        val blocked = listOf(
            "127.0.0.1",
            "10.1.2.3",
            "100.64.0.1",
            "169.254.1.1",
            "172.16.0.1",
            "192.168.1.1",
            "198.18.0.1",
            "192.0.2.1",
            "198.51.100.1",
            "203.0.113.1",
            "224.0.0.1",
        )
        blocked.forEach { address ->
            var transportCalls = 0
            val source = SafeHttpsNotificationMetadataSource(
                resolver = NotificationDnsResolver { _, _, _ -> listOf(ip(address)) },
                transport = PinnedHttpsMetadataTransport { _, _, _, _ ->
                    transportCalls += 1
                    html("<title>unsafe</title>")
                },
            )

            assertTrue(source.read(envelope("https://host.example.net")) is ReadOnlyEnrichmentResult.Unavailable)
            assertEquals("transport reached for $address", 0, transportCalls)
            assertFalse(PublicNetworkAddressPolicy.isPublic(ip(address)))
        }
    }

    @Test
    fun localAndEmbeddedIpv6AddressesAreRejected() {
        val blocked = listOf(
            "::",
            "::1",
            "fe80::1",
            "fc00::1",
            "fec0::1",
            "ff02::1",
            "2001:db8::1",
            "2001:2::1",
            "2001:5::1",
            "2001:4:112::1",
            "2001:6000::1",
            "3fff::1",
            "2004::1",
            "2003:4000::1",
            "2420::1",
            "2640::1",
            "2a20::1",
            "2d00::1",
            "3000::1",
            "2001:0000::1",
            "2002:0808:0808::1",
            "::ffff:127.0.0.1",
            "64:ff9b::7f00:1",
        )

        blocked.forEach { assertFalse("accepted $it", PublicNetworkAddressPolicy.isPublic(ip(it))) }
        assertTrue(PublicNetworkAddressPolicy.isPublic(ip("2606:4700:4700::1111")))
        assertTrue(PublicNetworkAddressPolicy.isPublic(ip("2001:4860:4860::8888")))
        assertTrue(PublicNetworkAddressPolicy.isPublic(ip("2003:3fff::1")))
        assertTrue(PublicNetworkAddressPolicy.isPublic(ip("2a00:1450:4001::1")))
        listOf(
            "2001:200::1", "2001:400::1", "2001:600::1", "2001:800::1",
            "2001:c00::1", "2001:e00::1", "2001:1200::1", "2001:1400::1",
            "2001:1800::1", "2001:1a00::1", "2001:1c00::1", "2001:2000::1",
            "2001:4000::1", "2001:4200::1", "2001:4400::1", "2001:4600::1",
            "2001:4800::1", "2001:4a00::1", "2001:4c00::1", "2001:5000::1",
            "2001:8000::1", "2001:a000::1", "2001:b000::1", "2003::1",
            "2400::1", "2410::1", "2600::1", "2610::1", "2620::1", "2630::1",
            "2800::1", "2a00::1", "2a10::1", "2c00::1",
        ).forEach { assertTrue("rejected allocated $it", PublicNetworkAddressPolicy.isPublic(ip(it))) }
        assertFalse(PublicNetworkAddressPolicy.isPublic(ip("2620:4f:8000::1")))
    }

    @Test
    fun activeNat64PrefixRechecksEmbeddedIpv4AndRejectsPrivateTargets() {
        val prefix = NotificationNat64Prefix(
            address = ip("64:ff9b:1::").address,
            prefixLength = 96,
        )

        assertFalse(
            PublicNetworkAddressPolicy.isPublic(ip("64:ff9b:1::c0a8:101"), prefix),
        )
        assertTrue(
            PublicNetworkAddressPolicy.isPublic(ip("64:ff9b:1::808:808"), prefix),
        )
        assertFalse(
            PublicNetworkAddressPolicy.isPublic(
                ip("64:ff9b:1::808:808"),
                prefix.copy(prefixLength = 64),
            ),
        )
        assertFalse(
            PublicNetworkAddressPolicy.isPublic(
                ip("64:ff9b:1::808:808"),
                prefix.copy(address = prefix.address.copyOf(12)),
            ),
        )
        assertFalse(
            PublicNetworkAddressPolicy.isPublic(ip("64:ff9b:1::6440:1"), prefix),
        )
        assertFalse(
            PublicNetworkAddressPolicy.isPublic(ip("64:ff9b:1::c000:201"), prefix),
        )
        val networkSpecific = NotificationNat64Prefix(
            address = ip("fd00:64::").address,
            prefixLength = 96,
        )
        assertTrue(
            PublicNetworkAddressPolicy.isPublic(ip("fd00:64::808:808"), networkSpecific),
        )
    }

    @Test
    fun mixedDnsAnswerFailsClosedAndCannotPickOnlyThePublicAddress() {
        var transportCalls = 0
        val source = SafeHttpsNotificationMetadataSource(
            resolver = NotificationDnsResolver { _, _, _ ->
                listOf(ip("8.8.8.8"), ip("10.0.0.1"))
            },
            transport = PinnedHttpsMetadataTransport { _, _, _, _ ->
                transportCalls += 1
                html("<title>bad</title>")
            },
        )

        assertTrue(source.read(envelope("https://mixed.example.net")) is ReadOnlyEnrichmentResult.Unavailable)
        assertEquals(0, transportCalls)
    }

    @Test
    fun eachHopIsRevalidatedAndPrivateRedirectNeverFetches() {
        val fetched = mutableListOf<String>()
        val source = SafeHttpsNotificationMetadataSource(
            resolver = NotificationDnsResolver { host, _, _ ->
                if (host == "public.example.net") listOf(ip("8.8.8.8")) else listOf(ip("10.0.0.2"))
            },
            transport = PinnedHttpsMetadataTransport { endpoint, _, _, _ ->
                fetched += endpoint.url.host
                redirect("https://private.example.net/admin")
            },
        )

        assertTrue(source.read(envelope("https://public.example.net/start")) is ReadOnlyEnrichmentResult.Unavailable)
        assertEquals(listOf("public.example.net"), fetched)
    }

    @Test
    fun pinnedResolutionPreventsDnsRebindingWithinRequest() {
        val lookups = AtomicInteger(0)
        var transportPinned: List<InetAddress> = emptyList()
        val source = SafeHttpsNotificationMetadataSource(
            resolver = NotificationDnsResolver { _, _, _ ->
                if (lookups.incrementAndGet() == 1) listOf(ip("8.8.8.8")) else listOf(ip("127.0.0.1"))
            },
            transport = PinnedHttpsMetadataTransport { endpoint, _, _, _ ->
                transportPinned = endpoint.pinnedAddresses
                html("<title>Safe metadata</title>")
            },
        )

        assertTrue(source.read(envelope("https://pin.example.net")) is ReadOnlyEnrichmentResult.Available)
        assertEquals(1, lookups.get())
        assertEquals(listOf(ip("8.8.8.8")), transportPinned)
    }

    @Test
    fun redirectBudgetLoopHttpCredentialsPortAndLocalNamesFailClosed() {
        val public = NotificationDnsResolver { _, _, _ -> listOf(ip("8.8.8.8")) }
        var redirectCalls = 0
        val redirecting = SafeHttpsNotificationMetadataSource(
            resolver = public,
            transport = PinnedHttpsMetadataTransport { endpoint, _, _, _ ->
                redirectCalls += 1
                redirect("https://${endpoint.url.host}/next-${endpoint.url.encodedPath.hashCode()}")
            },
        )
        assertTrue(redirecting.read(envelope("https://loop.example.net/start")) is ReadOnlyEnrichmentResult.Unavailable)
        assertEquals(4, redirectCalls)

        listOf(
            "http://public.example.net",
            "https://user:pass@public.example.net",
            "https://public.example.net:8443",
            "https://public.example.net/path?tracking=secret",
            "https://public.example.net/path#fragment",
            "https://public.example.net/t_abcdefghijklmnopqrstuvwxyz0123456789",
            "https://public.example.net/unsubscribe/account",
            "https://public.example.net/events/confirm",
            "https://public.example.net/%61%62%31%32",
            "https://public.example.net/%2525short-token",
            "https://localhost",
            "https://printer.local",
            "https://service.internal",
        ).forEach { url ->
            val source = SafeHttpsNotificationMetadataSource(
                resolver = public,
                transport = PinnedHttpsMetadataTransport { _, _, _, _ -> error("transport must not run") },
            )
            assertTrue("accepted $url", source.read(envelope(url)) is ReadOnlyEnrichmentResult.Unavailable)
        }
        val encodedToken = "abcdefghijklmnopqrstuvwxyz0123456789"
            .asSequence()
            .joinToString("") { "%%%02x".format(it.code) }
        val encodedSource = SafeHttpsNotificationMetadataSource(
            resolver = public,
            transport = PinnedHttpsMetadataTransport { _, _, _, _ -> error("transport must not run") },
        )
        assertTrue(
            encodedSource.read(envelope("https://public.example.net/$encodedToken"))
                is ReadOnlyEnrichmentResult.Unavailable,
        )
    }

    @Test
    fun oversizeWrongMimeCompressionAndMissingMetadataFailClosed() {
        val cases = listOf(
            BoundedMetadataHttpResponse(200, null, "application/json", null, 2, "{}".toByteArray()),
            BoundedMetadataHttpResponse(200, null, "text/html", "gzip", 5, "hello".toByteArray()),
            BoundedMetadataHttpResponse(200, null, "text/html", null, 200_000, ByteArray(0)),
            BoundedMetadataHttpResponse(200, null, "text/html", null, null, ByteArray(128 * 1_024 + 1)),
            BoundedMetadataHttpResponse(200, null, "text/html; charset=iso-8859-1", null, 6, "<html>".toByteArray()),
            BoundedMetadataHttpResponse(200, null, "text/html", null, 4, byteArrayOf(0x50, 0x4e, 0x47, 0x00)),
            BoundedMetadataHttpResponse(
                200,
                null,
                "text/html",
                null,
                8,
                byteArrayOf(0x3c, 0x68, 0x74, 0x6d, 0x6c, 0x3e, 0xc3.toByte(), 0x28),
            ),
            html("<html><body>No metadata</body></html>"),
        )
        cases.forEach { response ->
            val source = SafeHttpsNotificationMetadataSource(
                resolver = NotificationDnsResolver { _, _, _ -> listOf(ip("8.8.8.8")) },
                transport = PinnedHttpsMetadataTransport { _, _, _, _ -> response },
            )
            assertTrue(source.read(envelope("https://public.example.net")) is ReadOnlyEnrichmentResult.Unavailable)
        }
    }

    @Test
    fun requestedAdaptersOnlyAreReadAndFailuresAreExplicitlyBounded() {
        var httpsCalls = 0
        var profileCalls = 0
        var calendarCalls = 0
        var recentCalls = 0
        val provider = RootFreeNotificationEnrichmentProvider(
            httpsMetadata = NotificationHttpsMetadataSource { _, _ ->
                httpsCalls += 1
                ReadOnlyEnrichmentResult.Unavailable
            },
            confirmedProfile = ConfirmedNotificationProfileSource {
                profileCalls += 1
                ReadOnlyEnrichmentResult.Available(
                    "Alice interessiert sich fuer Konzerte. " + "A".repeat(10_000) +
                        ". Bob interessiert sich fuer Sport.",
                )
            },
            calendar = NotificationCalendarContextSource { _, _, _ ->
                calendarCalls += 1
                ReadOnlyEnrichmentResult.Available(emptyList())
            },
            recentNotifications = RecentNotificationContextSource { _, _, _ ->
                recentCalls += 1
                ReadOnlyEnrichmentResult.Available(emptyList())
            },
        )

        val evidence = provider.enrich(
            envelope("event"),
            RestrictedNotificationTriagePlan.Enrich(
                reason = "mixed_context_needed",
                urgency = NotificationUrgency.NORMAL,
                confidence = NotificationTriageConfidence.MEDIUM,
                adapters = setOf(
                    NotificationEnrichmentAdapterKind.HTTPS_METADATA,
                    NotificationEnrichmentAdapterKind.CONFIRMED_PROFILE,
                    NotificationEnrichmentAdapterKind.CALENDAR,
                ),
                entities = listOf("Alice"),
            ),
        )

        assertEquals(1, httpsCalls)
        assertEquals(1, profileCalls)
        assertEquals(1, calendarCalls)
        assertEquals(0, recentCalls)
        assertTrue(NotificationEnrichmentAdapterKind.HTTPS_METADATA in evidence.unavailableAdapters)
        assertTrue(requireNotNull(evidence.confirmedProfileSummary).toByteArray().size <= 4 * 1_024)
    }

    @Test
    fun profileOnlyWithoutMatchingEntityIsUnavailableAndCannotBypassSilentGate() {
        listOf(emptyList(), listOf("Charlie")).forEach { entities ->
            val provider = RootFreeNotificationEnrichmentProvider(
                httpsMetadata = NotificationHttpsMetadataSource { _, _ ->
                    ReadOnlyEnrichmentResult.Unavailable
                },
                confirmedProfile = ConfirmedNotificationProfileSource {
                    ReadOnlyEnrichmentResult.Available("Alice mag Konzerte. Bob mag Sport.")
                },
                calendar = NotificationCalendarContextSource { _, _, _ ->
                    ReadOnlyEnrichmentResult.Unavailable
                },
                recentNotifications = RecentNotificationContextSource { _, _, _ ->
                    ReadOnlyEnrichmentResult.Unavailable
                },
            )
            val evidence = provider.enrich(
                envelope("event"),
                RestrictedNotificationTriagePlan.Enrich(
                    reason = "profile_context_needed",
                    urgency = NotificationUrgency.NORMAL,
                    confidence = NotificationTriageConfidence.MEDIUM,
                    adapters = setOf(NotificationEnrichmentAdapterKind.CONFIRMED_PROFILE),
                    entities = entities,
                ),
            )

            assertEquals(null, evidence.confirmedProfileSummary)
            assertEquals(
                setOf(NotificationEnrichmentAdapterKind.CONFIRMED_PROFILE),
                evidence.unavailableAdapters,
            )
        }
    }

    @Test
    fun maliciousFamilyUpdateCannotProjectPrivateProfileSentences() {
        val provider = RootFreeNotificationEnrichmentProvider(
            httpsMetadata = NotificationHttpsMetadataSource { _, _ ->
                ReadOnlyEnrichmentResult.Unavailable
            },
            confirmedProfile = ConfirmedNotificationProfileSource {
                ReadOnlyEnrichmentResult.Available(
                    "Alice family lives at a private address. Alice prefers quiet concerts. " +
                        "Bob has an unrelated health update.",
                )
            },
            calendar = NotificationCalendarContextSource { _, _, _ ->
                ReadOnlyEnrichmentResult.Unavailable
            },
            recentNotifications = RecentNotificationContextSource { _, _, _ ->
                ReadOnlyEnrichmentResult.Unavailable
            },
        )

        listOf(
            listOf("family", "update"),
            listOf("Familie", "Aktualisierung"),
            listOf("health", "concerts"),
        ).forEach { genericEntities ->
            val evidence = provider.enrich(
                envelope("Family update"),
                RestrictedNotificationTriagePlan.Enrich(
                    reason = "profile_context_needed",
                    urgency = NotificationUrgency.NORMAL,
                    confidence = NotificationTriageConfidence.MEDIUM,
                    adapters = setOf(NotificationEnrichmentAdapterKind.CONFIRMED_PROFILE),
                    entities = genericEntities,
                ),
            )

            assertEquals(null, evidence.confirmedProfileSummary)
            assertEquals(
                setOf(NotificationEnrichmentAdapterKind.CONFIRMED_PROFILE),
                evidence.unavailableAdapters,
            )
        }

        val specific = provider.enrich(
            envelope("Message from Alice"),
            RestrictedNotificationTriagePlan.Enrich(
                reason = "profile_context_needed",
                urgency = NotificationUrgency.NORMAL,
                confidence = NotificationTriageConfidence.MEDIUM,
                adapters = setOf(NotificationEnrichmentAdapterKind.CONFIRMED_PROFILE),
                entities = listOf("Alice"),
            ),
        )
        assertTrue(requireNotNull(specific.confirmedProfileSummary).contains("Alice"))
        assertFalse(
            NotificationEnrichmentAdapterKind.CONFIRMED_PROFILE in specific.unavailableAdapters,
        )
    }

    @Test
    fun emptyRequestedAdaptersAreUnavailableRatherThanSyntheticEvidence() {
        val provider = RootFreeNotificationEnrichmentProvider(
            httpsMetadata = NotificationHttpsMetadataSource { _, _ ->
                ReadOnlyEnrichmentResult.Available(emptyList())
            },
            confirmedProfile = ConfirmedNotificationProfileSource {
                ReadOnlyEnrichmentResult.Unavailable
            },
            calendar = NotificationCalendarContextSource { _, _, _ ->
                ReadOnlyEnrichmentResult.Available(emptyList())
            },
            recentNotifications = RecentNotificationContextSource { _, _, _ ->
                ReadOnlyEnrichmentResult.Available(emptyList())
            },
        )

        listOf(
            NotificationEnrichmentAdapterKind.HTTPS_METADATA,
            NotificationEnrichmentAdapterKind.CALENDAR,
            NotificationEnrichmentAdapterKind.RECENT_NOTIFICATIONS,
        ).forEach { adapter ->
            val evidence = provider.enrich(
                envelope("event"),
                RestrictedNotificationTriagePlan.Enrich(
                    reason = "context_needed",
                    urgency = NotificationUrgency.NORMAL,
                    confidence = NotificationTriageConfidence.MEDIUM,
                    adapters = setOf(adapter),
                    entities = listOf("Alice"),
                ),
            )
            assertEquals(setOf(adapter), evidence.unavailableAdapters)
        }
    }

    @Test
    fun providerPreemptionCancelsWholeAdapterPlanBeforeNextAdapterStarts() {
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        var calendarCalls = 0
        val https = object : NotificationHttpsMetadataSource,
            PreemptibleNotificationEnrichmentProvider {
            override fun read(
                notification: UntrustedNotificationEnvelope,
                cancellation: NotificationEnrichmentCancellation,
            ): ReadOnlyEnrichmentResult<List<NotificationLinkMetadata>> {
                entered.countDown()
                released.await(1, TimeUnit.SECONDS)
                return ReadOnlyEnrichmentResult.Unavailable
            }

            override fun preemptCurrent() {
                released.countDown()
            }
        }
        val provider = RootFreeNotificationEnrichmentProvider(
            httpsMetadata = https,
            confirmedProfile = ConfirmedNotificationProfileSource {
                ReadOnlyEnrichmentResult.Unavailable
            },
            calendar = NotificationCalendarContextSource { _, _, _ ->
                calendarCalls += 1
                ReadOnlyEnrichmentResult.Available(emptyList())
            },
            recentNotifications = RecentNotificationContextSource { _, _, _ ->
                ReadOnlyEnrichmentResult.Unavailable
            },
        )
        val thread = Thread {
            provider.enrich(
                envelope("https://public.example.net"),
                RestrictedNotificationTriagePlan.Enrich(
                    reason = "context_needed",
                    urgency = NotificationUrgency.NORMAL,
                    confidence = NotificationTriageConfidence.MEDIUM,
                    adapters = linkedSetOf(
                        NotificationEnrichmentAdapterKind.HTTPS_METADATA,
                        NotificationEnrichmentAdapterKind.CALENDAR,
                    ),
                    entities = listOf("Alice"),
                ),
            )
        }
        thread.start()
        assertTrue(entered.await(1, TimeUnit.SECONDS))

        provider.preemptCurrent()
        thread.join(1_000)

        assertFalse(thread.isAlive)
        assertEquals(0, calendarCalls)
    }

    @Test
    fun totalRedirectChainDeadlineStopsBeforeAnotherHop() {
        val now = AtomicLong(0)
        var transportCalls = 0
        val source = SafeHttpsNotificationMetadataSource(
            resolver = NotificationDnsResolver { _, _, _ -> listOf(ip("8.8.8.8")) },
            transport = PinnedHttpsMetadataTransport { _, _, _, _ ->
                transportCalls += 1
                now.set(TimeUnit.SECONDS.toNanos(11))
                redirect("https://next.example.net")
            },
            nanoTime = now::get,
        )

        assertTrue(source.read(envelope("https://start.example.net")) is ReadOnlyEnrichmentResult.Unavailable)
        assertEquals(1, transportCalls)
    }

    @Test
    fun preemptionCancelsStuckDnsBeforeAnyHttpRequest() {
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val result = AtomicReference<ReadOnlyEnrichmentResult<List<NotificationLinkMetadata>>>()
        val resolver = object : NotificationDnsResolver, PreemptibleNotificationEnrichmentProvider {
            override fun lookup(
                hostname: String,
                timeoutMillis: Long,
                cancellation: NotificationEnrichmentCancellation,
            ): List<InetAddress> {
                entered.countDown()
                released.await(timeoutMillis, TimeUnit.MILLISECONDS)
                throw java.net.UnknownHostException("cancelled")
            }

            override fun preemptCurrent() {
                released.countDown()
            }
        }
        var transportCalls = 0
        val source = SafeHttpsNotificationMetadataSource(
            resolver = resolver,
            transport = PinnedHttpsMetadataTransport { _, _, _, _ ->
                transportCalls += 1
                html("<title>must not run</title>")
            },
        )
        val thread = Thread {
            result.set(source.read(envelope("https://blocked.example.net")))
        }
        thread.start()
        assertTrue(entered.await(1, TimeUnit.SECONDS))

        source.preemptCurrent()
        thread.join(1_000)

        assertFalse(thread.isAlive)
        assertTrue(result.get() is ReadOnlyEnrichmentResult.Unavailable)
        assertEquals(0, transportCalls)
    }

    @Test
    fun preemptionBetweenDnsAndTransportCannotStartHttp() {
        val transitionEntered = CountDownLatch(1)
        val releaseTransition = CountDownLatch(1)
        val result = AtomicReference<ReadOnlyEnrichmentResult<List<NotificationLinkMetadata>>>()
        var networkStarts = 0
        val source = SafeHttpsNotificationMetadataSource(
            resolver = NotificationDnsResolver { _, _, _ -> listOf(ip("8.8.8.8")) },
            transport = PinnedHttpsMetadataTransport { _, _, _, cancellation ->
                if (!cancellation.isCancelled()) networkStarts += 1
                html("<title>must not run</title>")
            },
            beforeTransportStart = {
                transitionEntered.countDown()
                releaseTransition.await(1, TimeUnit.SECONDS)
            },
        )
        val thread = Thread {
            result.set(source.read(envelope("https://transition.example.net")))
        }
        thread.start()
        assertTrue(transitionEntered.await(1, TimeUnit.SECONDS))

        source.preemptCurrent()
        releaseTransition.countDown()
        thread.join(1_000)

        assertFalse(thread.isAlive)
        assertTrue(result.get() is ReadOnlyEnrichmentResult.Unavailable)
        assertEquals(0, networkStarts)
    }

    @Test
    fun sameHostRedirectIsResolvedAgainAndPrivateRebindingStopsSecondTransport() {
        var dnsCalls = 0
        var transportCalls = 0
        val source = SafeHttpsNotificationMetadataSource(
            resolver = NotificationDnsResolver { _, _, _ ->
                dnsCalls += 1
                if (dnsCalls == 1) listOf(ip("8.8.8.8")) else listOf(ip("10.0.0.5"))
            },
            transport = PinnedHttpsMetadataTransport { _, _, _, _ ->
                transportCalls += 1
                redirect("/second-hop")
            },
        )

        assertTrue(source.read(envelope("https://rebind.example.net/start")) is ReadOnlyEnrichmentResult.Unavailable)
        assertEquals(2, dnsCalls)
        assertEquals(1, transportCalls)
    }

    @Test
    fun streamingBodyReaderRejectsUnknownLengthResponseAtTheByteBoundary() {
        val limit = 128 * 1_024
        assertEquals(limit, BoundedMetadataBodyReader.read(ByteArrayInputStream(ByteArray(limit)), limit).size)
        val failure = runCatching {
            BoundedMetadataBodyReader.read(ByteArrayInputStream(ByteArray(limit + 1)), limit)
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals("metadata_body_too_large", failure?.message)
    }

    @Test
    fun calendarProjectionHidesUnrelatedTitlesAndLocationsButKeepsBusyIntervals() {
        val unrelated = calendar("Dentist for Bob", "Private clinic", 1_000, 2_000)
        val related = calendar("Alice concert", "Town Hall", 3_000, 4_000)

        val projected = projectCalendarContexts(listOf(unrelated, related), listOf("Alice"))

        assertEquals(2, projected.size)
        assertEquals("", projected[0].title)
        assertEquals("", projected[0].location)
        assertEquals(1_000, projected[0].beginEpochMillis)
        assertEquals("Alice concert", projected[1].title)
        assertEquals("Town Hall", projected[1].location)
    }

    @Test
    fun maliciousMeetingReminderAndGenericTopicsRevealOnlyBusyIntervals() {
        val privateEvents = listOf(
            calendar("Acquisition meeting", "Secret board room", 1_000, 2_000),
            calendar("Privater Termin", "Home address", 3_000, 4_000),
            calendar("Confidential project", "Client office", 5_000, 6_000),
            calendar("Internal update", "Executive floor", 7_000, 8_000),
        )

        listOf(
            listOf("meeting", "reminder"),
            listOf("Termin"),
            listOf("project"),
            listOf("update"),
        ).forEach { genericEntities ->
            val projected = projectCalendarContexts(privateEvents, genericEntities)

            assertEquals(privateEvents.size, projected.size)
            assertTrue(projected.all { it.title.isEmpty() })
            assertTrue(projected.all { it.location.isEmpty() })
            assertEquals(
                privateEvents.map { it.beginEpochMillis to it.endEpochMillis },
                projected.map { it.beginEpochMillis to it.endEpochMillis },
            )
        }
    }

    @Test
    fun recentContextNeverUsesPackageAloneAcrossChatThreads() {
        val current = envelope("Dinner with Alice").copy(
            packageName = "com.whatsapp",
            androidKey = "thread-alice-current",
            title = "Alice",
        )
        val unrelatedBob = inboxEvent("com.whatsapp", "thread-bob", "Bob", "Private message")
        val relatedByStableKey = inboxEvent(
            "com.whatsapp",
            "thread-alice-current",
            "Different presentation",
            "Earlier message",
        )
        val relatedByEntity = inboxEvent(
            "com.whatsapp",
            "thread-alice-current",
            "Alice",
            "Earlier message",
        )

        assertFalse(isRelatedNotificationThread(unrelatedBob, current, listOf("Alice")))
        assertFalse(isRelatedNotificationThread(relatedByStableKey, current, emptyList()))
        assertTrue(isRelatedNotificationThread(relatedByEntity, current, listOf("Alice")))
        assertFalse(isRelatedNotificationThread(relatedByEntity, current, listOf("the")))
        assertFalse(
            isRelatedNotificationThread(
                inboxEvent(
                    "com.whatsapp",
                    "thread-alice-current",
                    "Bob Rechnung",
                    "Private Bob data about Rechnung",
                ),
                current.copy(title = "Alice Rechnung"),
                listOf("Rechnung"),
            ),
        )
        assertTrue(
            selectRecentNotificationEvents(
                listOf(
                    inboxEvent(
                        "com.whatsapp",
                        "thread-alice-current",
                        "Bob",
                        "Bob's private message",
                    ),
                ),
                current.copy(sourceSequence = 2),
                listOf("Alice"),
                0,
            ).isEmpty(),
        )

        val removal = inboxEvent(
            "com.whatsapp",
            "thread-alice-current",
            "Alice",
            "",
            sequence = 2,
            kind = NotificationEventKind.REMOVED,
        )
        val oldSameKey = relatedByStableKey.copy(sequence = 1)
        assertTrue(
            selectRecentNotificationEvents(
                listOf(oldSameKey, removal),
                current.copy(sourceSequence = 3),
                emptyList(),
                0,
            ).isEmpty(),
        )
    }

    @Test
    fun publicLinkMetadataIsDefaultOffSeparatelyGrantableAndRevocable() {
        val directory = Files.createTempDirectory("hans-notification-link-consent").toFile()
        try {
            val store = AtomicPersistentAndroidConsentStore(
                File(directory, AtomicPersistentAndroidConsentStore.FILE_NAME),
            )
            val reads = AtomicInteger(0)
            val source = ConsentGatedNotificationHttpsMetadataSource(
                consentStore = store,
                delegate = NotificationHttpsMetadataSource { _, _ ->
                    reads.incrementAndGet()
                    ReadOnlyEnrichmentResult.Available(
                        listOf(NotificationLinkMetadata("https://example.org", "Title", "Summary")),
                    )
                },
            )
            val descriptor = PersistentAndroidConsentDescriptor.category(
                PersistentAndroidConsentScope.NOTIFICATION_LINK_METADATA,
            )

            assertTrue(source.read(envelope("https://example.org"), NotificationEnrichmentCancellation.NONE) is ReadOnlyEnrichmentResult.Unavailable)
            assertEquals(0, reads.get())
            assertTrue(store.grant(descriptor))
            assertTrue(source.read(envelope("https://example.org"), NotificationEnrichmentCancellation.NONE) is ReadOnlyEnrichmentResult.Available)
            assertEquals(1, reads.get())
            assertTrue(store.revoke(descriptor))
            assertTrue(source.read(envelope("https://example.org"), NotificationEnrichmentCancellation.NONE) is ReadOnlyEnrichmentResult.Unavailable)
            assertEquals(1, reads.get())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun revokingLinkMetadataConsentCancelsAnInFlightReadAndSuppressesItsResult() {
        val directory = Files.createTempDirectory("hans-notification-link-revoke").toFile()
        try {
            val store = AtomicPersistentAndroidConsentStore(
                File(directory, AtomicPersistentAndroidConsentStore.FILE_NAME),
            )
            val descriptor = PersistentAndroidConsentDescriptor.category(
                PersistentAndroidConsentScope.NOTIFICATION_LINK_METADATA,
            )
            assertTrue(store.grant(descriptor))
            val delegateStarted = CountDownLatch(1)
            val allowDelegateReturn = CountDownLatch(1)
            val cancellationObserved = AtomicBoolean(false)
            val source = ConsentGatedNotificationHttpsMetadataSource(
                consentStore = store,
                delegate = NotificationHttpsMetadataSource { _, cancellation ->
                    delegateStarted.countDown()
                    assertTrue(allowDelegateReturn.await(2, TimeUnit.SECONDS))
                    cancellationObserved.set(cancellation.isCancelled())
                    ReadOnlyEnrichmentResult.Available(
                        listOf(NotificationLinkMetadata("https://example.org", "Title", "Summary")),
                    )
                },
            )
            val result = AtomicReference<ReadOnlyEnrichmentResult<List<NotificationLinkMetadata>>>()
            val thread = Thread {
                result.set(
                    source.read(
                        envelope("https://example.org"),
                        NotificationEnrichmentCancellation.NONE,
                    ),
                )
            }

            thread.start()
            assertTrue(delegateStarted.await(2, TimeUnit.SECONDS))
            assertTrue(store.revoke(descriptor))
            allowDelegateReturn.countDown()
            thread.join(2_000)

            assertFalse(thread.isAlive)
            assertTrue(cancellationObserved.get())
            assertTrue(result.get() is ReadOnlyEnrichmentResult.Unavailable)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun authorityLeaseRemainsRevocableAfterEnrichmentReturnsButBeforeSynthesis() {
        val directory = Files.createTempDirectory("hans-notification-authority-lease").toFile()
        try {
            val store = AtomicPersistentAndroidConsentStore(
                File(directory, AtomicPersistentAndroidConsentStore.FILE_NAME),
            )
            val descriptor = PersistentAndroidConsentDescriptor.category(
                PersistentAndroidConsentScope.NOTIFICATION_LINK_METADATA,
            )
            assertTrue(store.grant(descriptor))
            val provider = RootFreeNotificationEnrichmentProvider(
                httpsMetadata = ConsentGatedNotificationHttpsMetadataSource(
                    consentStore = store,
                    delegate = NotificationHttpsMetadataSource { _, _ ->
                        ReadOnlyEnrichmentResult.Available(
                            listOf(NotificationLinkMetadata("https://example.org", "Title", "Summary")),
                        )
                    },
                ),
                confirmedProfile = ConfirmedNotificationProfileSource {
                    ReadOnlyEnrichmentResult.Unavailable
                },
                calendar = NotificationCalendarContextSource { _, _, _ ->
                    ReadOnlyEnrichmentResult.Unavailable
                },
                recentNotifications = RecentNotificationContextSource { _, _, _ ->
                    ReadOnlyEnrichmentResult.Unavailable
                },
            )
            val evidence = provider.enrich(
                envelope("https://example.org/event"),
                RestrictedNotificationTriagePlan.Enrich(
                    reason = "link_context_needed",
                    urgency = NotificationUrgency.NORMAL,
                    confidence = NotificationTriageConfidence.MEDIUM,
                    adapters = setOf(NotificationEnrichmentAdapterKind.HTTPS_METADATA),
                    entities = emptyList(),
                ),
            )

            assertTrue(evidence.authorityLease.isValid())
            assertTrue(store.revoke(descriptor))
            assertFalse(evidence.authorityLease.isValid())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun revokingCalendarConsentOrRuntimePermissionSuppressesAnInFlightRead() {
        listOf("durable_consent", "runtime_permission").forEach { revokedAuthority ->
            val directory = Files.createTempDirectory("hans-notification-calendar-revoke").toFile()
            try {
                val store = AtomicPersistentAndroidConsentStore(
                    File(directory, AtomicPersistentAndroidConsentStore.FILE_NAME),
                )
                val descriptor = PersistentAndroidConsentDescriptor.category(
                    PersistentAndroidConsentScope.READ_CALENDAR,
                )
                assertTrue(store.grant(descriptor))
                val permissionGranted = AtomicBoolean(true)
                val delegateStarted = CountDownLatch(1)
                val allowDelegateReturn = CountDownLatch(1)
                val cancellationObserved = AtomicBoolean(false)
                val source = ConsentGatedNotificationCalendarSource(
                    consentStore = store,
                    runtimePermissionGranted = permissionGranted::get,
                    delegate = NotificationCalendarContextSource { _, _, cancellation ->
                        delegateStarted.countDown()
                        assertTrue(allowDelegateReturn.await(2, TimeUnit.SECONDS))
                        cancellationObserved.set(cancellation.isCancelled())
                        ReadOnlyEnrichmentResult.Available(
                            listOf(
                                NotificationCalendarContext(
                                    beginEpochMillis = 1L,
                                    endEpochMillis = 2L,
                                    allDay = false,
                                    title = "Private title",
                                    location = "Private location",
                                ),
                            ),
                        )
                    },
                )
                val result = AtomicReference<ReadOnlyEnrichmentResult<List<NotificationCalendarContext>>>()
                val thread = Thread {
                    result.set(
                        source.read(
                            envelope("Calendar update"),
                            listOf("Private"),
                            NotificationEnrichmentCancellation.NONE,
                        ),
                    )
                }

                thread.start()
                assertTrue(delegateStarted.await(2, TimeUnit.SECONDS))
                when (revokedAuthority) {
                    "durable_consent" -> assertTrue(store.revoke(descriptor))
                    "runtime_permission" -> permissionGranted.set(false)
                    else -> error("unknown test authority")
                }
                allowDelegateReturn.countDown()
                thread.join(2_000)

                assertFalse(thread.isAlive)
                assertTrue(cancellationObserved.get())
                assertTrue(result.get() is ReadOnlyEnrichmentResult.Unavailable)
                source.close()
            } finally {
                directory.deleteRecursively()
            }
        }
    }

    @Test
    fun providerClosePreemptsAndClosesEveryOwnedSourceExactlyOnce() {
        val closes = AtomicInteger(0)
        val preempts = AtomicInteger(0)
        val https = object : NotificationHttpsMetadataSource,
            PreemptibleNotificationEnrichmentProvider,
            Closeable {
            override fun read(
                notification: UntrustedNotificationEnvelope,
                cancellation: NotificationEnrichmentCancellation,
            ) = ReadOnlyEnrichmentResult.Available(emptyList<NotificationLinkMetadata>())

            override fun preemptCurrent() {
                preempts.incrementAndGet()
            }

            override fun close() {
                closes.incrementAndGet()
            }
        }
        val profile = object : ConfirmedNotificationProfileSource, Closeable {
            override fun read() = ReadOnlyEnrichmentResult.Unavailable
            override fun close() {
                closes.incrementAndGet()
            }
        }
        val calendar = object : NotificationCalendarContextSource, Closeable {
            override fun read(
                notification: UntrustedNotificationEnvelope,
                entities: List<String>,
                cancellation: NotificationEnrichmentCancellation,
            ) = ReadOnlyEnrichmentResult.Available(emptyList<NotificationCalendarContext>())

            override fun close() {
                closes.incrementAndGet()
            }
        }
        val recent = object : RecentNotificationContextSource, Closeable {
            override fun read(
                notification: UntrustedNotificationEnvelope,
                entities: List<String>,
                cancellation: NotificationEnrichmentCancellation,
            ) = ReadOnlyEnrichmentResult.Available(emptyList<RecentNotificationContext>())

            override fun close() {
                closes.incrementAndGet()
            }
        }
        val provider = RootFreeNotificationEnrichmentProvider(https, profile, calendar, recent)

        provider.close()
        provider.close()

        assertEquals(1, preempts.get())
        assertEquals(4, closes.get())
        val afterClose = provider.enrich(
            envelope("https://example.org"),
            RestrictedNotificationTriagePlan.Enrich(
                reason = "link_context_needed",
                urgency = NotificationUrgency.NORMAL,
                confidence = NotificationTriageConfidence.MEDIUM,
                adapters = setOf(NotificationEnrichmentAdapterKind.HTTPS_METADATA),
                entities = emptyList(),
            ),
        )
        assertEquals(
            setOf(NotificationEnrichmentAdapterKind.HTTPS_METADATA),
            afterClose.unavailableAdapters,
        )
    }

    @Test
    fun closeWinningBeforeOperationRegistrationPreventsEveryAdapterRead() {
        val registrationEntered = CountDownLatch(1)
        val releaseRegistration = CountDownLatch(1)
        val reads = AtomicInteger(0)
        val provider = RootFreeNotificationEnrichmentProvider(
            httpsMetadata = NotificationHttpsMetadataSource { _, _ ->
                reads.incrementAndGet()
                ReadOnlyEnrichmentResult.Available(emptyList())
            },
            confirmedProfile = ConfirmedNotificationProfileSource {
                reads.incrementAndGet()
                ReadOnlyEnrichmentResult.Unavailable
            },
            calendar = NotificationCalendarContextSource { _, _, _ ->
                reads.incrementAndGet()
                ReadOnlyEnrichmentResult.Available(emptyList())
            },
            recentNotifications = RecentNotificationContextSource { _, _, _ ->
                reads.incrementAndGet()
                ReadOnlyEnrichmentResult.Available(emptyList())
            },
            beforeOperationRegistration = {
                registrationEntered.countDown()
                releaseRegistration.await(5, TimeUnit.SECONDS)
            },
        )
        val finished = CountDownLatch(1)
        val result = AtomicReference<NotificationEnrichmentEvidence>()
        Thread {
            result.set(
                provider.enrich(
                    envelope("https://example.org"),
                    RestrictedNotificationTriagePlan.Enrich(
                        reason = "link_context_needed",
                        urgency = NotificationUrgency.NORMAL,
                        confidence = NotificationTriageConfidence.MEDIUM,
                        adapters = setOf(NotificationEnrichmentAdapterKind.HTTPS_METADATA),
                        entities = emptyList(),
                    ),
                ),
            )
            finished.countDown()
        }.start()
        assertTrue(registrationEntered.await(2, TimeUnit.SECONDS))

        provider.close()
        releaseRegistration.countDown()

        assertTrue(finished.await(2, TimeUnit.SECONDS))
        assertEquals(0, reads.get())
        assertEquals(
            setOf(NotificationEnrichmentAdapterKind.HTTPS_METADATA),
            requireNotNull(result.get()).unavailableAdapters,
        )
    }

    private fun envelope(text: String) = UntrustedNotificationEnvelope(
        sourceSequence = 10,
        kind = NotificationEventKind.POSTED,
        observedAtEpochMillis = 1_000,
        packageName = "com.example.chat",
        androidKey = "key",
        title = "Event",
        text = text,
        subtext = "",
        category = "message",
        channelId = "messages",
        ongoing = false,
        clearable = true,
    )

    private fun calendar(
        title: String,
        location: String,
        begin: Long,
        end: Long,
    ) = CalendarInstance(
        eventId = "event-$begin",
        calendarId = "calendar",
        title = title,
        location = location,
        organizer = "",
        beginEpochMillis = begin,
        endEpochMillis = end,
        allDay = false,
        status = 1,
    )

    private fun inboxEvent(
        packageName: String,
        androidKey: String,
        title: String,
        text: String,
        sequence: Long = 1,
        kind: NotificationEventKind = NotificationEventKind.POSTED,
    ) = NotificationInboxEvent(
        sequence = sequence,
        kind = kind,
        observedAtEpochMillis = 500,
        removalReason = null,
        snapshot = NotificationSnapshot(
            packageName = packageName,
            androidKey = androidKey,
            postTimeEpochMillis = 500,
            notificationWhenEpochMillis = 500,
            title = title,
            text = text,
            subtext = "",
            category = "message",
            channelId = "messages",
            ongoing = false,
            clearable = true,
            actions = emptyList(),
        ),
    )

    private fun html(value: String): BoundedMetadataHttpResponse {
        val document = if (value.trimStart().startsWith("<html", ignoreCase = true) ||
            value.trimStart().startsWith("<!doctype", ignoreCase = true)
        ) {
            value
        } else {
            "<html><head>$value</head></html>"
        }
        return BoundedMetadataHttpResponse(
        statusCode = 200,
        location = null,
        contentType = "text/html; charset=utf-8",
        contentEncoding = "identity",
        declaredContentLength = document.toByteArray().size.toLong(),
        body = document.toByteArray(),
    )
    }

    private fun redirect(location: String) = BoundedMetadataHttpResponse(
        statusCode = 302,
        location = location,
        contentType = null,
        contentEncoding = null,
        declaredContentLength = 0,
        body = ByteArray(0),
    )

    private fun ip(value: String): InetAddress = InetAddress.getByName(value)
}
