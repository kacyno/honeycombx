package data.sync.disruptor;

import com.lmax.disruptor.EventHandler;

/**
 * Created by hesiyuan on 15/7/6.
 */
public class LongEventHandler implements EventHandler<LongEvent>
{
    public void onEvent(LongEvent event, long sequence, boolean endOfBatch)
    {
        if(event.value==99999999)
            System.out.println("finish");
//        System.out.println("Event: " + event);
    }
}