#include "window_jni.cpp"
#include <cstdio>

int main() {
    int failures = 0;
    auto check = [&](bool value, const char* label) {
        if (!value) { ++failures; std::fprintf(stderr, "%s\n", label); }
    };
    check(is_touch_mouse_message(WM_LBUTTONDOWN, 0xff515780), "touch promoted press must be suppressed");
    check(is_touch_mouse_message(WM_MOUSEMOVE, 0xff515781), "touch promoted movement must be suppressed");
    check(!is_touch_mouse_message(WM_LBUTTONDOWN, 0xff515700), "pen mouse input must be preserved");
    check(!is_touch_mouse_message(WM_LBUTTONDOWN, 0), "real mouse input must be preserved");
    check(!is_touch_mouse_message(WM_MOUSEWHEEL, 0xff515780), "wheel input must be preserved");
    check(!is_touch_mouse_message(WM_MOUSEHWHEEL, 0xff515780), "horizontal wheel must be preserved");
    check(!cancels_touch(nullptr, WM_CAPTURECHANGED, 0), "mouse capture changes must not cancel native touch");
    check(cancels_touch(nullptr, WM_CANCELMODE, 0), "modal cancellation must cancel native touch");
    check(cancels_touch(nullptr, WM_SHOWWINDOW, 0), "hiding must cancel native touch");
    check(!cancels_touch(nullptr, WM_SHOWWINDOW, 1), "showing must not cancel native touch");
    check(touch_phase(TOUCHEVENTF_DOWN) == 0, "touch down phase");
    check(touch_phase(TOUCHEVENTF_MOVE) == 1, "touch move phase");
    check(touch_phase(TOUCHEVENTF_UP) == 2, "touch release phase");
    check(touch_phase(TOUCHEVENTF_PRIMARY) == -1, "non-contact flags must not become moves");
    const auto point = touch_client_coordinates(-1050, 20425, POINT{-20, 100});
    check(std::abs(point.first - 9.5f) < .001f && std::abs(point.second - 104.25f) < .001f,
        "screen hundredths convert to client pixels without DPI rescaling or signed truncation");
    WNDCLASSW window_class{};
    window_class.hInstance = GetModuleHandleW(nullptr);
    window_class.lpfnWndProc = DefWindowProcW;
    window_class.lpszClassName = L"BitChordTouchFixture";
    check(RegisterClassW(&window_class) != 0, "register private fixture window class");
    const HWND window = CreateWindowExW(0, window_class.lpszClassName, L"", WS_POPUP,
        -1000, -1000, 100, 100, nullptr, nullptr, window_class.hInstance, nullptr);
    check(window != nullptr, "create invisible fixture window");
    check(configure_touch(window, true), "register native touch on owning thread");
    check(IsTouchWindow(window, nullptr) != FALSE, "touch registration must reach the HWND");
    check(g_touch_message_hook != nullptr && g_touch_windows.count(window) == 1,
        "registered canvas must own the AWT-thread message filter");
    check(configure_touch(window, true) && g_touch_windows.size() == 1, "registration must be idempotent");
    check(SendMessageW(window, WM_TOUCH, 257, 0) == 0, "corrupt packet counts are rejected safely");
    check(configure_touch(window, false), "unregister native touch on owning thread");
    check(IsTouchWindow(window, nullptr) == FALSE, "disabled bridge restores native fallback");
    check(g_touch_message_hook == nullptr && g_touch_windows.empty(), "last detach must retire the message filter");
    const ULONG original_flags = TWF_FINETOUCH | TWF_WANTPALM;
    check(RegisterTouchWindow(window, original_flags) != FALSE, "register existing AWT-style touch ownership");
    check(configure_touch(window, true) && configure_touch(window, false), "existing registration can be borrowed and restored");
    ULONG restored_flags = 0;
    check(IsTouchWindow(window, &restored_flags) != FALSE && restored_flags == original_flags,
        "detach must preserve the exact previous touch flags");
    UnregisterTouchWindow(window);
    const HWND child = CreateWindowExW(0, window_class.lpszClassName, L"", WS_CHILD,
        0, 0, 20, 20, window, nullptr, window_class.hInstance, nullptr);
    check(!cancels_touch(window, WM_KILLFOCUS, reinterpret_cast<WPARAM>(child)), "focus within the same window is not loss of touch ownership");
    check(configure_touch(window, true) && configure_touch(child, true) && g_touch_windows.size() == 2,
        "root and popup canvases can share one owning-thread filter");
    check(configure_touch(window, false) && g_touch_message_hook != nullptr && g_touch_windows.size() == 1,
        "one canvas retiring must not detach another");
    DestroyWindow(child);
    check(g_touch_message_hook == nullptr && g_touch_windows.empty(), "native destruction must retire the final canvas filter");
    check(!configure_touch(nullptr, true), "invalid window must not register");
    DestroyWindow(window);
    std::printf("touch fixture: %d failures\n", failures);
    return failures ? 1 : 0;
}
