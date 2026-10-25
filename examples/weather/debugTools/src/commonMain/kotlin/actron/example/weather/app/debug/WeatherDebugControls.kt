package actron.example.weather.app.debug

internal data class WeatherDebugControls(
    val canAccessFiles: Boolean,
    val showDiagnosticDemo: Boolean,
)

internal fun weatherDebugControls(
    visible: Boolean,
    viewer: WeatherRecordingViewer,
): WeatherDebugControls = WeatherDebugControls(canAccessFiles = !viewer.busy, showDiagnosticDemo = visible && viewer.imported.isEmpty())

internal fun WeatherHistory.setPanelVisible(
    visible: Boolean,
    viewer: WeatherRecordingViewer,
) {
    panelVisible.value = visible
    if (!visible) {
        panel.returnToLive()
        viewer.close()
    }
}
