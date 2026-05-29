package madacode.bootstrap;

import madacode.tool.WebFetchTool;

final class WebToolModule implements ToolModule {

    @Override
    public void install(ToolContext context) {
        context.register(new WebFetchTool());
    }
}
