#include "window_jni.cpp"

#include <cstdio>
#include <iterator>
#include <future>
#include <chrono>
#include <cstdlib>

namespace {
class PrivateDesktop {
    HDESK original_ = GetThreadDesktop(GetCurrentThreadId());
    HDESK desktop_ = nullptr;
public:
    PrivateDesktop() {
        wchar_t name[64]{};
        swprintf_s(name, L"BitChordWindowFrameTest-%lu", GetCurrentProcessId());
        desktop_ = CreateDesktopW(name, nullptr, nullptr, 0, GENERIC_ALL, nullptr);
        if (desktop_ && !SetThreadDesktop(desktop_)) {
            CloseDesktop(desktop_);
            desktop_ = nullptr;
        }
    }
    ~PrivateDesktop() {
        if (desktop_) {
            SetThreadDesktop(original_);
            CloseDesktop(desktop_);
        }
    }
    bool ready() const { return desktop_ != nullptr; }
};

LRESULT CALLBACK test_frame_proc(HWND window, UINT message, WPARAM wparam, LPARAM lparam) {
    // Synthetic activation messages must not make DefWindowProc move keyboard focus.
    if (message == WM_ACTIVATE) return 0;
    return DefWindowProcW(window, message, wparam, lparam);
}

bool expect(bool condition, const char* label) {
    if (!condition) std::fprintf(stderr, "%s\n", label);
    return condition;
}

bool expect_bounds(HWND window, const RECT& expected, const char* label) {
    RECT actual{};
    return expect(GetWindowRect(window, &actual) && EqualRect(&actual, &expected), label);
}

bool is_topmost(HWND window) {
    return (GetWindowLongPtrW(window, GWL_EXSTYLE) & WS_EX_TOPMOST) != 0;
}

bool precedes(HWND first, HWND second) {
    for (HWND next = GetWindow(first, GW_HWNDNEXT); next; next = GetWindow(next, GW_HWNDNEXT)) {
        if (next == second) return true;
    }
    return false;
}

bool expect_hit(HWND window, POINT point, LRESULT expected, const char* label) {
    const LRESULT actual = SendMessageW(window, WM_NCHITTEST, 0, MAKELPARAM(point.x, point.y));
    if (actual == expected) return true;
    std::fprintf(stderr, "%s: hit %lld, expected %lld\n", label,
        static_cast<long long>(actual), static_cast<long long>(expected));
    return false;
}
}

int main() {
    // Never switch the input desktop: fullscreen test windows stay off the user's desktop.
    PrivateDesktop desktop;
    if (!desktop.ready()) {
        std::fprintf(stderr, "Could not create private test desktop: %lu\n", GetLastError());
        return 2;
    }
    const HINSTANCE instance = GetModuleHandleW(nullptr);
    WNDCLASSW frame_class{};
    frame_class.hInstance = instance;
    frame_class.lpfnWndProc = test_frame_proc;
    frame_class.lpszClassName = L"BitChordWindowFrameTest";
    WNDCLASSW canvas_class = frame_class;
    canvas_class.lpszClassName = L"SunAwtCanvas";
    if (!RegisterClassW(&frame_class) || !RegisterClassW(&canvas_class)) return 2;
    HWND frame = CreateWindowExW(0, frame_class.lpszClassName, L"BitChord frame test",
        WS_OVERLAPPEDWINDOW, -1000, -1000, 800, 600, nullptr, nullptr, instance, nullptr);
    HWND canvas = CreateWindowExW(0, canvas_class.lpszClassName, L"", WS_CHILD,
        0, 0, 800, 600, frame, nullptr, instance, nullptr);
    if (!frame || !canvas || !install_frame(frame)) return 2;
    RECT bounds{};
    GetWindowRect(frame, &bounds);
    const int cx = (bounds.left + bounds.right) / 2;
    const int cy = (bounds.top + bounds.bottom) / 2;
    const POINT points[] = {
        {bounds.left + 1, cy}, {bounds.right - 2, cy},
        {cx, bounds.top + 1}, {cx, bounds.bottom - 2},
        {bounds.left + 1, bounds.top + 1}, {bounds.right - 2, bounds.top + 1},
        {bounds.left + 1, bounds.bottom - 2}, {bounds.right - 2, bounds.bottom - 2},
    };
    const LRESULT expected[] = {HTLEFT, HTRIGHT, HTTOP, HTBOTTOM,
        HTTOPLEFT, HTTOPRIGHT, HTBOTTOMLEFT, HTBOTTOMRIGHT};
    bool passed = true;
    for (size_t index = 0; index < std::size(points); ++index) {
        passed &= expect_hit(frame, points[index], expected[index], "frame edge");
        passed &= expect_hit(canvas, points[index], HTTRANSPARENT, "canvas edge");
    }
    passed &= expect_hit(canvas, {cx, cy}, HTCLIENT, "canvas center");
    HWND replacement = CreateWindowExW(0, canvas_class.lpszClassName, L"", WS_CHILD,
        0, 0, 800, 600, frame, nullptr, instance, nullptr);
    passed &= replacement != nullptr;
    if (replacement) {
        passed &= expect_hit(replacement, points[0], HTTRANSPARENT, "new canvas edge");
        passed &= expect_hit(replacement, {cx, cy}, HTCLIENT, "new canvas center");
    }
    ShowWindow(frame, SW_SHOWNOACTIVATE);
    const LONG_PTR original_style = GetWindowLongPtrW(frame, GWL_STYLE);
    HWND peer = CreateWindowExW(0, frame_class.lpszClassName, L"BitChord fullscreen peer",
        WS_OVERLAPPEDWINDOW, 20, 20, 400, 300, nullptr, nullptr, instance, nullptr);
    if (!peer) return 2;
    ShowWindow(peer, SW_SHOWNOACTIVATE);
    passed &= expect(set_fullscreen(frame, true), "Entering fullscreen failed");
    passed &= expect(!is_topmost(frame), "Inactive fullscreen entry became topmost");
    const RECT target = g_fullscreen_target;
    MONITORINFO monitor{sizeof(MONITORINFO)};
    GetMonitorInfoW(MonitorFromWindow(frame, MONITOR_DEFAULTTONEAREST), &monitor);
    passed &= expect(target.left < monitor.rcMonitor.left && target.top < monitor.rcMonitor.top &&
        target.right > monitor.rcMonitor.right && target.bottom > monitor.rcMonitor.bottom,
        "Fullscreen target must overshoot all monitor edges");

    SendMessageW(frame, WM_ACTIVATE, WA_ACTIVE, reinterpret_cast<LPARAM>(peer));
    passed &= expect(is_topmost(frame), "Activated fullscreen did not cover the taskbar band");
    passed &= expect_bounds(frame, target, "Activated fullscreen lost its pinned bounds");
    const POINT fullscreen_edge{target.left + 1, target.top + 100};
    passed &= expect_hit(canvas, fullscreen_edge, HTCLIENT, "fullscreen canvas edge");
    passed &= expect_hit(replacement, fullscreen_edge, HTCLIENT, "fullscreen replacement canvas edge");

    SendMessageW(frame, WM_ACTIVATE, WA_INACTIVE, reinterpret_cast<LPARAM>(peer));
    passed &= expect(!is_topmost(frame), "Deactivated fullscreen remained topmost");
    WINDOWPOS inactive_update{frame, HWND_TOPMOST, 100, 100, 200, 200, 0};
    SendMessageW(frame, WM_WINDOWPOSCHANGING, 0, reinterpret_cast<LPARAM>(&inactive_update));
    passed &= expect((inactive_update.flags & SWP_NOACTIVATE) != 0,
        "Inactive position update could activate the window");
    SetWindowPos(peer, HWND_TOP, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE);
    passed &= expect(precedes(peer, frame), "Ordinary peer could not cover inactive fullscreen");
    SetWindowPos(frame, HWND_TOPMOST, 100, 100, 200, 200, SWP_NOACTIVATE);
    passed &= expect(!is_topmost(frame), "Inactive position update restored topmost status");
    passed &= expect(precedes(peer, frame), "Inactive position update covered the ordinary peer");
    passed &= expect_bounds(frame, target, "Inactive fullscreen lost its pinned bounds");

    SendMessageW(frame, WM_ACTIVATE, WA_CLICKACTIVE, reinterpret_cast<LPARAM>(peer));
    passed &= expect(is_topmost(frame), "Click reactivation did not restore topmost fullscreen");
    SetWindowPos(frame, HWND_NOTOPMOST, 100, 100, 200, 200,
        SWP_NOACTIVATE | SWP_NOZORDER | SWP_NOMOVE | SWP_NOSIZE);
    passed &= expect(is_topmost(frame), "Active position update lost topmost status");
    passed &= expect_bounds(frame, target, "Active position update lost its pinned bounds");

    SetActiveWindow(frame);
    SetActiveWindow(peer);
    passed &= expect(GetActiveWindow() == peer && !is_topmost(frame),
        "Native activation of the ordinary peer did not demote fullscreen");
    SetActiveWindow(frame);
    passed &= expect(GetActiveWindow() == frame && is_topmost(frame),
        "Native reactivation of fullscreen did not restore topmost status");
    passed &= expect_bounds(frame, target, "Native reactivation lost the fullscreen bounds");

    passed &= expect(set_fullscreen(frame, false), "Exiting fullscreen failed");
    passed &= expect(!is_topmost(frame), "Fullscreen exit did not restore normal z-order band");
    passed &= expect(GetWindowLongPtrW(frame, GWL_STYLE) == original_style,
        "Fullscreen exit did not restore the window style");
    passed &= expect_bounds(frame, bounds, "Fullscreen exit did not restore the window bounds");
    passed &= expect_hit(canvas, points[0], HTTRANSPARENT, "restored canvas edge");
    DestroyWindow(peer);
    DestroyWindow(frame);
    {
        std::lock_guard<std::mutex> audio_busy(g_audio_mutex);
        auto discovery = std::async(std::launch::async, [] { return discover_device_values(); });
        auto metadata = std::async(std::launch::async, [] { return device_name_snapshot(); });
        if (discovery.wait_for(std::chrono::seconds(5)) != std::future_status::ready ||
            metadata.wait_for(std::chrono::seconds(1)) != std::future_status::ready) {
            std::fprintf(stderr, "Device discovery or UI metadata waited for the playback lock\n");
            std::_Exit(1);
        }
        passed &= discovery.get().size() % 2 == 0;
        passed &= metadata.get().empty();
    }
    if (passed) std::puts("Window frame: eight edges, client center, and recreated canvas passed");
    if (passed) std::puts("Fullscreen: inactive demotion, ordinary peer, active pinning, and exit restoration passed");
    if (passed) std::puts("Audio discovery and UI metadata remain independent of the playback lock");
    return passed ? 0 : 1;
}
