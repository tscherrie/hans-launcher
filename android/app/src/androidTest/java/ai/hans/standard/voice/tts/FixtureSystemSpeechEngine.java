package ai.hans.standard.voice.tts;

import android.media.AudioFormat;
import android.speech.tts.SynthesisCallback;
import android.speech.tts.SynthesisRequest;
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeechService;
import android.speech.tts.Voice;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Test APK service runs outside instrumentation: Android/Java only, no target-app classpath. */
public final class FixtureSystemSpeechEngine extends TextToSpeechService {
    private volatile boolean stopped;
    @Override protected int onIsLanguageAvailable(String language, String country, String variant) {
        return Arrays.asList("deu", "ger", "de", "eng", "en").contains(language)
            ? TextToSpeech.LANG_COUNTRY_AVAILABLE : TextToSpeech.LANG_NOT_SUPPORTED;
    }
    @Override protected String[] onGetLanguage() { return new String[]{"deu", "DEU", ""}; }
    @Override protected int onLoadLanguage(String language, String country, String variant) {
        return onIsLanguageAvailable(language, country, variant);
    }
    @Override public List<Voice> onGetVoices() {
        return Arrays.asList(
            new Voice("hans-test-de", Locale.GERMANY, Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, Collections.emptySet()),
            new Voice("hans-test-en", Locale.US, Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, true, Collections.emptySet()));
    }
    @Override public String onGetDefaultVoiceNameFor(String l, String c, String v) { return "hans-test-de"; }
    @Override public int onIsValidVoiceName(String name) {
        return "hans-test-de".equals(name) || "hans-test-en".equals(name) ? TextToSpeech.SUCCESS : TextToSpeech.ERROR;
    }
    @Override public int onLoadVoice(String name) { return onIsValidVoiceName(name); }
    @Override protected void onStop() { stopped = true; }
    @Override protected void onSynthesizeText(SynthesisRequest request, SynthesisCallback callback) {
        stopped = false;
        String text = request.getCharSequenceText().toString();
        try {
            if ("fail".equals(text)) { callback.error(); return; }
            callback.start(16000, AudioFormat.ENCODING_PCM_16BIT, 1);
            byte[] frame = new byte[320];
            int count = "long".equals(text) ? 1000 : 10;
            for (int i = 0; i < count; i++) {
                if (stopped || callback.audioAvailable(frame, 0, frame.length) != TextToSpeech.SUCCESS) return;
            }
        } finally {
            // Public synthesis contract requires done even after error or cancellation.
            callback.done();
        }
    }
}
