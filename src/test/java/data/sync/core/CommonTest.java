package data.sync.core;

import data.sync.common.ClusterMessages;
import data.sync.common.NetUtil;
import org.junit.Test;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Created by hesiyuan on 15/6/26.
 */
public class CommonTest {
    @Test
    public void test1(){
        String tem = "select * from a where a=b";
        System.out.println("aaaa"+tem.substring(tem.indexOf("where")));
    }
}
