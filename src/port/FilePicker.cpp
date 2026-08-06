#include "port/FilePicker.h"

#if LIGHTHOUSE_NATIVE_FILE_DIALOG
#include <string>
#include <vector>
#include "portable-file-dialogs.h"
#include "port/DevTools/ThreadWatchdog.h"
#endif

namespace fs = std::filesystem;

namespace Lighthouse {

#ifdef __ANDROID__
namespace Android {
// Implemented in port/android/AndroidBridge.cpp, which owns the JNI plumbing.
// Returns false when no pick could be started (no activity yet, or one already
// in flight), so the caller can fall back rather than drop the request. Takes
// the callback by reference and only moves out of it once the pick is actually
// under way, so the fallback path still has one to hand to the ImGui browser.
bool RequestDocumentPick(std::function<void(std::optional<fs::path>)>& onResult);
void PumpDocumentPick();
} // namespace Android
#endif

#if LIGHTHOUSE_NATIVE_FILE_DIALOG
// portable-file-dialogs takes a flat filter list: { label, "*.a *.b", label2, "*.c", ... }.
static std::vector<std::string> ToPfdFilters(const std::vector<Ship::FileFilter>& filters) {
    std::vector<std::string> out;
    for (const auto& filter : filters) {
        out.push_back(filter.Label);
        std::string patterns;
        for (const auto& pattern : filter.Patterns) {
            if (!patterns.empty()) {
                patterns += " ";
            }
            patterns += pattern;
        }
        out.push_back(patterns);
    }
    if (out.empty()) {
        out = { "All Files", "*" };
    }
    return out;
}
#endif

void PickFile(Ship::FileBrowserRequest request, std::function<void(std::optional<fs::path>)> onResult) {
#ifdef __ANDROID__
    // Open mode goes to the system document picker, which is the only way to
    // reach a file the app does not already own — scoped storage leaves the
    // ImGui browser able to see little more than the app's own directory, so a
    // ROM sitting in Downloads or on Drive would be unreachable without this.
    if (!request.Save && Android::RequestDocumentPick(onResult)) {
        return;
    }
    // Save mode, or no activity to ask: fall through to the ImGui browser.
#endif
#if LIGHTHOUSE_NATIVE_FILE_DIALOG
    const std::string startDir = request.StartDir.empty() ? "." : request.StartDir.string();
    const std::vector<std::string> filters = ToPfdFilters(request.Filters);

    std::optional<fs::path> result;
    {
        Lighthouse::ExpectedStall watchdogPause("native file dialog");
        if (request.Save) {
            const std::string defaultPath = (fs::path(startDir) / request.DefaultName).string();
            std::string selection = pfd::save_file(request.Title, defaultPath, filters).result();
            if (!selection.empty()) {
                result = fs::path(selection);
            }
        } else {
            std::vector<std::string> selection = pfd::open_file(request.Title, startDir, filters).result();
            if (!selection.empty()) {
                result = fs::path(selection.front());
            }
        }
    }
    if (onResult) {
        onResult(result);
    }
#else
    request.OnResult = std::move(onResult);
    Ship::FileBrowserWindow::Open(std::move(request));
#endif
}

void PumpFilePicker() {
#ifdef __ANDROID__
    Android::PumpDocumentPick();
#endif
}

} // namespace Lighthouse
