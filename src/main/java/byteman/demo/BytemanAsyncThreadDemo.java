package byteman.demo;

import java.util.concurrent.atomic.AtomicInteger;

public class BytemanAsyncThreadDemo extends Thread{

    private final AtomicInteger integerValue;

    BytemanAsyncThreadDemo(AtomicInteger integerValue) {
        this.integerValue = integerValue;
        setDaemon(true);
    }

    @Override
    public void run() {

        integerValue.incrementAndGet();
    }

}
