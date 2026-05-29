package madacode.bootstrap;

import madacode.tool.BashTool;
import madacode.tool.FileEditTool;
import madacode.tool.FileReadTool;
import madacode.tool.FileWriteTool;
import madacode.tool.GlobTool;
import madacode.tool.GrepTool;

final class FileToolModule implements ToolModule {

    @Override
    public void install(ToolContext context) {
        context.register(new BashTool());
        context.register(new FileReadTool());
        context.register(new FileWriteTool());
        context.register(new FileEditTool());
        context.register(new GlobTool());
        context.register(new GrepTool());
    }
}
