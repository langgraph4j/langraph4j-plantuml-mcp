package org.bsc.langgraph4j.mcp;

import io.javelit.core.Jt;
import io.javelit.core.Server;

public class PlantumlEmbedApp {

    public static void main( String[] args ) {

        var app = new PlantumlApp();

        // prepare a Javelit server
        var server = Server.builder(app::view, 8888).build();

        // start the server - this is non-blocking, user thread
        server.start();
    }

}
