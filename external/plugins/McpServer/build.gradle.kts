// Root project of the ACSIDE MCP Server plugin.
// All real configuration lives in :main (the plugin module packaged into McpServer.acp).
tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
