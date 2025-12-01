package org.bsc.langgraph4j.mcp;

//DEPS org.bsc.langgraph4j:langgraph4j-plantuml-mcp-server:1.0-SNAPSHOT

import io.javelit.core.Jt;
import org.bsc.async.AsyncGenerator;
import org.bsc.async.AsyncGeneratorQueue;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;


public class AsyncApp {
    public static void main( String[] args ) {

        var app = new AsyncApp();

        app.view();
    }

    @SuppressWarnings("unchecked")
    AsyncGenerator.WithResult<String> processProgress() {
        return (AsyncGenerator.WithResult<String>) Jt.sessionState().computeIfAbsent("process_progress", b ->
                new AsyncGenerator.WithResult<>(new AsyncGeneratorQueue.Generator<String>( new LinkedBlockingQueue<>(10) )) );
    }

    BlockingQueue<AsyncGenerator.Data<String>> processProgressQueue() {
        return ((AsyncGeneratorQueue.Generator<String>) processProgress().delegate()).queue();
    }

    record TaskState(String key ) {

        public void start() {
            Jt.sessionState().computeIfAbsentBoolean(key, b -> true);
        }
        public boolean started() {
            return Jt.sessionState().getBoolean( key, false);
        }
        public void stop() {
            Jt.sessionState().computeIfAbsentBoolean( key, b -> false);
        }

    }

    // the Javelit webapp
    void  view() {

        var workflowTaskState = new TaskState("workflow");

        Jt.title( "PlantUML App").use();

        Jt.divider("hr2").use();
        var plantumlResult = Jt.empty().key("plantumlResult").use();


        var buttonState = Jt.button( "Process Image")
                .disabled( workflowTaskState.started() )
                .use();
        if( buttonState ) {
            var processProgressInfo = Jt.empty().key("processProgressInfo").use();

            if( !workflowTaskState.started() ) {

                    workflowTaskState.start();
                    try {

                        var disposable = startAsyncTask(processProgressQueue());

                        for (var notification : processProgress()) {
                            Jt.info( "process progress: %s".formatted(notification) )
                                    .use(processProgressInfo);
                        }

                        var result = processProgress().resultValue().orElse("<NONE>");

                        Jt.text(String.valueOf(result)).use(plantumlResult);
                    } catch (Exception ex) {
                        Jt.error(ex.getMessage()).use(processProgressInfo);
                    } finally {
                        workflowTaskState.stop();
                    }
                }

        }

    }

    Disposable startAsyncTask( BlockingQueue<AsyncGenerator.Data<String>> notificationQueue )
    {

        var future = CompletableFuture.supplyAsync(() -> {

            System.out.printf( "startAsyncTask[%s]%n", Thread.currentThread().getName());
            try {
                var offerSuccess = notificationQueue.offer( AsyncGenerator.Data.of( "start" ));
                for( var i = 1 ; i <= 10 ; ++i ) {
                    Thread.sleep(1000);
                    offerSuccess = notificationQueue.offer( AsyncGenerator.Data.of( "Item[%d]".formatted(i)));
                    System.out.printf("Item[%d] - sent[%b]%n", i, offerSuccess);
                }
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            return "COMPLETE";
        });

        return Mono.fromFuture( future )
        .subscribe( value -> {
            var offerSuccess = notificationQueue.offer(AsyncGenerator.Data.done(value));
        }, ex -> {
            var offerSuccess = notificationQueue.offer(AsyncGenerator.Data.error(ex));
        });

    }

}
