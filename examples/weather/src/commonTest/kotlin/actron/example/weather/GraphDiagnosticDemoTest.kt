package actron.example.weather

import actron.example.weather.core.debug.*
import actron.example.weather.feature.cities.presentation.CitySearchMachine
import actron.example.weather.feature.weather.presentation.CityWeatherMachine
import actron.statechart.*
import actron.timetravel.compose.HistoryGraph
import kotlin.test.*

class GraphDiagnosticDemoTest {
    @Test
    fun realWeatherChartsAreCleanAndSimulationNeverMutatesThem() {
        for (chart in listOf(CitySearchMachine.create().chart, CityWeatherMachine.create().chart)) {
            assertTrue(chart.diagnostics.isEmpty(), chart.diagnostics.toString())
            val graph = HistoryGraph.Machine(chart, emptySet())
            assertSame(graph, graph.withDiagnosticDemo(GraphDiagnosticDemo.Off))
            val demo = graph.withDiagnosticDemo(GraphDiagnosticDemo.Warnings) as HistoryGraph.Machine
            assertTrue(demo.definition.diagnostics.any { it.code == GraphIssueCode.Unreachable })
            assertTrue(demo.definition.diagnostics.any { it.code == GraphIssueCode.NoExit })
            assertTrue(demo.definition.diagnostics.any { it.code == GraphIssueCode.ShadowedTransition })
            val broken = graph.withDiagnosticDemo(GraphDiagnosticDemo.Errors) as HistoryGraph.Machine
            assertTrue(broken.definition.diagnostics.any { it.severity == GraphIssueSeverity.Error })
            assertTrue(chart.diagnostics.isEmpty())
            assertEquals(chart.states.size + 2, demo.definition.states.size)
        }
    }
}
