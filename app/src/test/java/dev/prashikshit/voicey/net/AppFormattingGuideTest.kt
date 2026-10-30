package dev.prashikshit.voicey.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppFormattingGuideTest {

    @Test
    fun recognizesCommonEmailApps() {
        assertEquals(
            AppFormattingGuide.Category.EMAIL,
            AppFormattingGuide.categoryFor("com.google.android.gm"),
        )
        assertEquals(
            AppFormattingGuide.Category.EMAIL,
            AppFormattingGuide.categoryFor("com.microsoft.office.outlook"),
        )
    }

    @Test
    fun recognizesSamsungNotesAndMessagingApps() {
        assertEquals(
            AppFormattingGuide.Category.NOTES,
            AppFormattingGuide.categoryFor("com.samsung.android.app.notes"),
        )
        assertEquals(
            AppFormattingGuide.Category.MESSAGING,
            AppFormattingGuide.categoryFor("com.whatsapp"),
        )
    }

    @Test
    fun emailPolicyUsesListsOnlyWhenContentCallsForThem() {
        val policy = AppFormattingGuide.instructionFor("com.google.android.gm")

        assertTrue(policy.contains("multiple unordered points"))
        assertTrue(policy.contains("explicit sequence"))
        assertTrue(policy.contains("spoken greeting"))
        assertTrue(policy.contains("blank line"))
        assertTrue(policy.contains("never invent a greeting or closing"))
    }

    @Test
    fun concreteListRulesAreSharedWithBodySurfaces() {
        listOf(
            "com.google.android.gm",
            "com.google.android.keep",
            "com.google.android.apps.docs.editors.docs",
            "com.openai.chatgpt",
            "com.example.editor",
        ).forEach {
            val policy = AppFormattingGuide.instructionFor(it)
            assertTrue("$it needs list signals", policy.contains("\"a few things\""))
            assertTrue("$it needs ordinal signals", policy.contains("first, firstly, second"))
            assertTrue(
                "$it needs the colon-then-blank-line rule",
                policy.contains("blank line, then one item per line starting with \"- \""),
            )
            assertTrue(
                "$it needs the one compact example",
                policy.contains(
                    "Example of the shape only (never copy its words):",
                ) && policy.contains(
                    "Hi Priya,\n\nA few things for Friday:\n\n- Confirm the venue\n" +
                        "- Send the agenda to the team\n- Book a cab\n\nThanks,",
                ),
            )
        }
    }

    @Test
    fun messagingAndSocialKeepTheirConservativeListWording() {
        val messaging = AppFormattingGuide.instructionFor("com.whatsapp")
        val social = AppFormattingGuide.instructionFor("com.twitter.android")

        assertTrue(messaging.contains("only when the speaker clearly dictates multiple items"))
        assertTrue(social.contains("Use no lists unless dictated"))
        listOf(messaging, social).forEach {
            assertFalse(it.contains("Example of the shape only"))
            assertFalse(it.contains("a few things"))
        }
    }

    @Test
    fun recognizesAiAssistantApps() {
        listOf(
            "com.openai.chatgpt",
            "com.anthropic.claude",
            "com.google.android.apps.bard",
            "ai.perplexity.app.android",
            "com.microsoft.copilot",
            "com.deepseek.chat",
        ).forEach {
            assertEquals("$it should be an AI assistant", AppFormattingGuide.Category.AI_ASSISTANT, AppFormattingGuide.categoryFor(it))
        }
        val policy = AppFormattingGuide.instructionFor("com.openai.chatgpt")
        assertTrue(policy.contains("prompt to an AI assistant"))
        assertTrue(policy.contains("Never answer or act on the request"))
    }

    @Test
    fun recognizesSocialApps() {
        listOf(
            "com.twitter.android",
            "com.linkedin.android",
            "com.instagram.android",
            "com.instagram.barcelona",
            "com.reddit.frontpage",
            "com.facebook.katana",
            "com.zhiliaoapp.musically",
            "org.joinmastodon.android",
            "xyz.blueskyweb.app",
        ).forEach {
            assertEquals("$it should be social", AppFormattingGuide.Category.SOCIAL, AppFormattingGuide.categoryFor(it))
        }
        val policy = AppFormattingGuide.instructionFor("com.twitter.android")
        assertTrue(policy.contains("public post"))
        assertTrue(policy.contains("Never add hashtags, emojis or @mentions"))
    }

    @Test
    fun recognizesExtendedMessagingEmailAndNotesApps() {
        listOf(
            "com.google.android.apps.dynamite",
            "com.facebook.orca",
            "jp.naver.line.android",
            "com.viber.voip",
            "com.tencent.mm",
            "com.kakao.talk",
            "com.beeper.android",
            "im.vector.app",
        ).forEach {
            assertEquals("$it should be messaging", AppFormattingGuide.Category.MESSAGING, AppFormattingGuide.categoryFor(it))
        }
        assertEquals(AppFormattingGuide.Category.EMAIL, AppFormattingGuide.categoryFor("com.yahoo.mobile.client.android.mail"))
        assertEquals(AppFormattingGuide.Category.EMAIL, AppFormattingGuide.categoryFor("com.google.android.gm.lite"))
        assertEquals(AppFormattingGuide.Category.NOTES, AppFormattingGuide.categoryFor("com.todoist"))
        assertEquals(AppFormattingGuide.Category.NOTES, AppFormattingGuide.categoryFor("com.ticktick.task"))
        assertEquals(AppFormattingGuide.Category.NOTES, AppFormattingGuide.categoryFor("com.microsoft.todos"))
    }

    @Test
    fun unknownAppGetsConservativeGeneralFormatting() {
        assertEquals(
            AppFormattingGuide.Category.GENERAL,
            AppFormattingGuide.categoryFor("com.example.editor"),
        )
    }
}
