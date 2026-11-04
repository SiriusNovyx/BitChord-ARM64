#include "window_jni.cpp"
#include <cstdio>

int main() {
    int failures = 0;
    auto check = [&](const char* actual, const char* expected, const char* label) {
        if (std::strcmp(actual, expected) != 0) { ++failures; std::fprintf(stderr, "%s: %s\n", label, actual); }
    };
    check(classify_audio_route(L"USB", Headphones), "USB", "explicit USB enumerator");
    check(classify_audio_route(L"BTHENUM", Headphones), "BLUETOOTH", "explicit Bluetooth enumerator");
    check(classify_audio_route(L"SWD", Headphones), "HEADPHONES", "software endpoint does not prove transport");
    check(classify_audio_route(L"", Speakers), "SPEAKERS", "observed speaker form factor");
    check(classify_audio_route(L"", DigitalAudioDisplayDevice), "HDMI", "observed display-audio form factor");
    check(classify_audio_route(L"", SPDIF), "SPDIF", "observed SPDIF form factor");
    check(classify_audio_route(L"Bluetooth friendly name", UnknownFormFactor), "UNKNOWN", "a name is not a transport property");
    check(classify_audio_route(L"", LineLevel), "OTHER", "known other form factor");
    g_audio_route = "USB";
    release_client_locked();
    check(g_audio_route.c_str(), "UNKNOWN", "closing or losing the stream clears the observed route");
    std::printf("audio route fixture: %d failures\n", failures);
    return failures ? 1 : 0;
}
