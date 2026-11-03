package com.gamecore.pluginmaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sentences, checked without a phone.
 *
 * These are pure functions on purpose: the one thing a person making a plugin
 * needs from the maker, before they spend an hour on it, is to be told the
 * truth about whether it will run on the phone in their hand. A sentence that
 * is wrong, or that quietly says "installed" when nothing is installed, is
 * worse than no sentence at all — so it is worth a test that needs no device.
 */
class GameCoreInstallTest {

    @Test
    fun installedGameCore_namesTheVersionAndTheVersionCode() {
        val sentence = installedGameCoreSentence("4.0.0", 50L)

        assertTrue("the version name is missing: $sentence", sentence.contains("4.0.0"))
        assertTrue("the version code is missing: $sentence", sentence.contains("50"))
        assertTrue("it does not say GameCore: $sentence", sentence.contains("GameCore"))
    }

    @Test
    fun installedGameCore_saysThePluginWillRun() {
        val sentence = installedGameCoreSentence("4.0.0", 50L)

        assertTrue("it does not say the plugin will run: $sentence", sentence.contains("will run"))
    }

    @Test
    fun installedGameCore_withoutAVersionName_stillReadsAsGameCore() {
        val sentence = installedGameCoreSentence("", 50L)

        assertTrue("it does not say GameCore: $sentence", sentence.contains("GameCore"))
        assertTrue("the version code is missing: $sentence", sentence.contains("50"))
        assertFalse("it printed an empty version: $sentence", sentence.contains("GameCore  "))
    }

    @Test
    fun notInstalled_isSaidPlainly() {
        val sentence = gameCoreStatusSentence(null)

        assertEquals(MakerStrings.GAME_CORE_NOT_INSTALLED, sentence)
        assertTrue("it does not say it is not installed: $sentence", sentence.contains("not installed"))
        assertTrue("it does not name GameCore: $sentence", sentence.contains("GameCore"))
    }

    @Test
    fun notInstalled_doesNotPromiseThatThePluginWillRun() {
        val sentence = gameCoreStatusSentence(null)

        assertFalse("it promises the plugin will run: $sentence", sentence.contains("will run here"))
    }

    @Test
    fun statusSentence_isTheInstalledOneWhenGameCoreIsThere() {
        val installed = InstalledGameCore(versionName = "4.0.0", versionCode = 50L)

        assertEquals(installedGameCoreSentence("4.0.0", 50L), gameCoreStatusSentence(installed))
    }

    @Test
    fun statusSentence_isTheNotInstalledOneWhenGameCoreIsNotThere() {
        assertEquals(MakerStrings.GAME_CORE_NOT_INSTALLED, gameCoreStatusSentence(null))
    }

    @Test
    fun saveConfirmation_namesTheFileAndSaysHowToInstallIt() {
        val confirmation = MakerStrings.saveConfirmation("com.example.mytheme-1.0.0.gcplugin")

        assertTrue("the file name is missing: $confirmation", confirmation.contains("com.example.mytheme-1.0.0.gcplugin"))
        assertTrue("it does not say where the file went: $confirmation", confirmation.contains("folder"))
        assertTrue("it does not say how to install it: $confirmation", confirmation.contains("Choose files"))
    }
}
