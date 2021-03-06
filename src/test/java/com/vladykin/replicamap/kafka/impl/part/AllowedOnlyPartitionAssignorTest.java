package com.vladykin.replicamap.kafka.impl.part;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.kafka.common.Cluster;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class AllowedOnlyPartitionAssignorTest {

    // ConsumerPartitionAssignor is only available in 2.4.1, so using reflection
    // to be able to compile the test with both 2.3.1 and 2.4.1 Kafka versions.
    static final Constructor<?> GRP_SUB_CONSTRUCTOR;
    static final Method GRP_SUB_UNWRAPPER;

    static {
        Constructor<?> constructor = null;
        Method unwrapper = null;
        try {
            String prefix = "org.apache.kafka.clients.consumer.ConsumerPartitionAssignor$";

            constructor = Class.forName(prefix + "GroupSubscription")
                .getConstructor(Map.class);

            unwrapper = Class.forName(prefix + "GroupAssignment")
                .getDeclaredMethod("groupAssignment");
        }
        catch (ClassNotFoundException e) {
            // ignore
        }
        catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }

        GRP_SUB_CONSTRUCTOR = constructor;
        GRP_SUB_UNWRAPPER = unwrapper;
    }

    private static final String TOPIC_1 = "testTopic1";
    private static final String TOPIC_2 = "testTopic2";
    private static final List<String> TOPICS_LIST = Arrays.asList(TOPIC_1, TOPIC_2);
    private static final Set<String> TOPICS_SET = new HashSet<>(TOPICS_LIST);

    @Test
    void testParseAllowedPartitions() {
        AllowedOnlyPartitionAssignor a = new AllowedOnlyPartitionAssignor();

        assertArrayEquals(null, a.parseAllowedPartitions(null));

        assertArrayEquals(new short[]{}, a.parseAllowedPartitions(new short[]{}));
        assertArrayEquals(new short[]{}, a.parseAllowedPartitions(new int[]{}));
        assertArrayEquals(new short[]{}, a.parseAllowedPartitions(new long[]{}));
        assertArrayEquals(new short[]{}, a.parseAllowedPartitions(" "));
        assertArrayEquals(new short[]{}, a.parseAllowedPartitions(new String[]{}));
        assertArrayEquals(new short[]{}, a.parseAllowedPartitions(new Number[]{}));
        assertArrayEquals(new short[]{}, a.parseAllowedPartitions(new Long[]{}));
        assertArrayEquals(new short[]{}, a.parseAllowedPartitions(new Integer[]{}));
        assertArrayEquals(new short[]{}, a.parseAllowedPartitions(new BigDecimal[]{}));
        assertArrayEquals(new short[]{}, a.parseAllowedPartitions(new BigInteger[]{}));
        assertArrayEquals(new short[]{}, a.parseAllowedPartitions(new AtomicInteger[]{}));
        assertArrayEquals(new short[]{}, a.parseAllowedPartitions(new ArrayList<>()));
        assertArrayEquals(new short[]{}, a.parseAllowedPartitions(Collections.emptySet()));

        assertArrayEquals(new short[]{0,1,2,3}, a.parseAllowedPartitions(new short[]{3,0,1,1,2}));
        assertArrayEquals(new short[]{0,1,2,3}, a.parseAllowedPartitions(new int[]{3,0,1,1,2}));
        assertArrayEquals(new short[]{0,1,2,3}, a.parseAllowedPartitions(new long[]{3,0,1,1,2}));

        assertArrayEquals(new short[]{0,1,2,3}, a.parseAllowedPartitions(" 3,0, 1,1,2 "));
        assertArrayEquals(new short[]{0,1,2,3}, a.parseAllowedPartitions(new String[]{" 3","0"," 1"," 1 ","2 "}));
        assertArrayEquals(new short[]{0,1,2,3}, a.parseAllowedPartitions(new Long[]{3L,0L,1L,1L,2L}));
        assertArrayEquals(new short[]{0,1,2,3}, a.parseAllowedPartitions(new Integer[]{3,0,1,1,2}));

        assertArrayEquals(new short[]{0,1,2,3}, a.parseAllowedPartitions(Arrays.asList(3L,0L,1L,1L,2L)));
        assertArrayEquals(new short[]{0,1,2,3}, a.parseAllowedPartitions(Arrays.asList(3,0,1,1,2)));

        assertArrayEquals(new short[]{0,1,2,3}, a.parseAllowedPartitions(Stream.of(3,0,1,1,2)
            .map(BigDecimal::new).collect(Collectors.toList())));
        assertArrayEquals(new short[]{0,1,2,3}, a.parseAllowedPartitions(Stream.of("3","0","1","1","2")
            .map(BigInteger::new).collect(Collectors.toSet())));
        assertArrayEquals(new short[]{0,1,2,3}, a.parseAllowedPartitions(Stream.of(3,0,1,1,2)
            .map(AtomicInteger::new).collect(Collectors.toList())));

        assertArrayEquals(new short[]{7}, a.parseAllowedPartitions(7));
        assertArrayEquals(new short[]{9}, a.parseAllowedPartitions(new BigDecimal(9)));

        assertThrows(IllegalArgumentException.class, () -> a.parseAllowedPartitions("bla"));
        assertThrows(IllegalArgumentException.class, () -> a.parseAllowedPartitions(Arrays.asList("aa", "bb")));
        assertThrows(IllegalArgumentException.class, () -> a.parseAllowedPartitions(new long[]{-1}));
        assertThrows(IllegalArgumentException.class, () -> a.parseAllowedPartitions(new int[]{Short.MAX_VALUE + 1}));
        assertThrows(IllegalArgumentException.class, () -> a.parseAllowedPartitions(new BigDecimal(Short.MAX_VALUE + 1L)));
    }

    @Test
    void testAssignor() {
        assertEq(new short[][]{
                {0,3,4},
                {1,2}
            },
            run(5,
                createAssignor(null),
                createAssignor(new short[]{1,2})));

        assertEq(new short[][]{
                {3,5},
                {1,2}
            },
            run(7, 4,
                createAssignor(new short[]{1,3,5}),
                createAssignor(new short[]{1,2})));

        assertEq(new short[][]{
                {0,4,6},
                {3,5},
                {1,2},
            },
            run(7,
                createAssignor(null),
                createAssignor(new short[]{1,3,5}),
                createAssignor(new short[]{1,2})));

        assertEq(new short[][]{
                {6},
                {3,5},
                {1,2},
            },
            run(7, 5,
                createAssignor(new short[]{6}),
                createAssignor(new short[]{1,3,5}),
                createAssignor(new short[]{1,2})));

        assertEq(new short[][]{
                {1,5},
                {2,3}
            },
            run(7, 4,
                createAssignor(new short[]{1,3,5}),
                createAssignor(new short[]{2,3})));

        assertEq(new short[][]{
                {1,3},
                {2}
            },
            run(7, 3,
                createAssignor(new short[]{1,3}),
                createAssignor(new short[]{2,3})));

        assertEq(new short[][]{
                {1},
                {3}
            },
            run(5, 2,
                createAssignor(new short[]{1,3}),
                createAssignor(new short[]{1,3})));

        assertEq(new short[][]{
                {0,3},
                {1,}
            },
            run(5, 3,
                createAssignor(new short[]{0,1,3}),
                createAssignor(new short[]{0,1,3})));

        assertEq(new short[][]{
                {0,2},
                {1,3}
            },
            run(5, 4,
                createAssignor(new short[]{0,1,2,3}),
                createAssignor(new short[]{0,1,2,3})));

        assertEq(new short[][]{
                {0,2,4},
                {1,3}
            },
            run(5,
                createAssignor(new short[]{0,1,2,3,4}),
                createAssignor(new short[]{0,1,2,3,4})));

        assertEq(new short[][]{
                {1,3,4},
                {0,2}
            },
            run(5,
                createAssignor(new short[]{0,1,2,3,4}),
                createAssignor(new short[]{0,1,2,3})));

        assertEq(new short[][]{ // fixed broken config
                {0,2},
                {1,3}
            },
            run(4,
                createAssignor(new short[]{0,1,2,3,4}),
                createAssignor(new short[]{0,1,2,3})));

        assertEq(new short[][]{
                {},
                {1,2,3}
            },
            run(4, 3,
                createAssignor(new short[]{}),
                createAssignor(new short[]{1,2,3})));

        assertEq(new short[][]{
                {},
                {}
            },
            run(4, 0,
                createAssignor(new short[]{}),
                createAssignor(new short[]{})));

        assertEq(new short[][]{
                {0,1},
                {2,3}
            },
            run(4,
                createAssignor(new short[]{0,1,2,3}),
                createAssignor(new short[]{2,3})));

        assertEq(new short[][]{
                {0,3},
                {1,2}
            },
            run(4,
                createAssignor(new short[]{0,1,3}),
                createAssignor(new short[]{1,2})));

        assertEq(new short[][]{
                {0},
                {1,2}
            },
            run(4, 3,
                createAssignor(new short[]{0,1}),
                createAssignor(new short[]{1,2})));

        assertEq(new short[][]{
                {0,2},
                {1}
            },
            run(4, 3,
                createAssignor(new short[]{0,1,2}),
                createAssignor(new short[]{1,2})));

        assertEq(new short[][]{
                {0,2},
                {1,3}
            },
            run(4,
                createAssignor(new short[]{0,1,2}),
                createAssignor(new short[]{1,2,3})));

        assertEq(new short[][]{
                {2},
                {1,3},
                {0,4}
            },
            run(5,
                createAssignor(new short[]{2}),
                createAssignor(new short[]{1,2,3}),
                createAssignor(new short[]{0,1,3,4})));

        assertEq(new short[][]{
                {2},
                {3},
                {0,1,4}
            },
            run(5,
                createAssignor(new short[]{2}),
                createAssignor(new short[]{2,3}),
                createAssignor(new short[]{0,1,3,4})));

        assertEq(new short[][]{
                {3},
                {2},
                {0,1,4}
            },
            run(5,
                createAssignor(new short[]{3}),
                createAssignor(new short[]{2,3}),
                createAssignor(new short[]{0,1,3,4})));

        assertEq(new short[][]{
                {3},
                {2},
                {0}
            },
            run(5, 3,
                createAssignor(new short[]{3}),
                createAssignor(new short[]{2,3}),
                createAssignor(new short[]{0})));

        assertEq(new short[][]{
                {4},
                {2,3},
                {0,1}
            },
            run(5,
                createAssignor(new short[]{4}),
                createAssignor(new short[]{2,3}),
                createAssignor(new short[]{0,1,3,4})));

        assertEq(new short[][]{
                {4},
                {2,3},
                {0}
            },
            run(5, 4,
                createAssignor(new short[]{4}),
                createAssignor(new short[]{2,3}),
                createAssignor(new short[]{0,4})));

        assertEq(new short[][]{
                {3},
                {4},
                {1,2},
                {0}
            },
            run(5,
                createAssignor(new short[]{3,4}),
                createAssignor(new short[]{3,4}),
                createAssignor(new short[]{1,2,3}),
                createAssignor(new short[]{0,1,3,4})));

        assertEq(new short[][]{
                {3},
                {4},
                {0,2},
                {1}
            },
            run(5,
                createAssignor(new short[]{3,4}),
                createAssignor(new short[]{3,4}),
                createAssignor(new short[]{0,1,2,3}),
                createAssignor(new short[]{0,1,3,4})));

        assertEq(new short[][]{
                {3},
                {4},
                {1,2,5}, // this skew is because 2 and 5 are available only to this assignor
                {0}
            },
            run(6,
                createAssignor(new short[]{3,4}),
                createAssignor(new short[]{3,4}),
                createAssignor(new short[]{0,1,2,3,5}),
                createAssignor(new short[]{0,1,3,4})));

        assertEq(new short[][]{
                {0,2},
                {1,3,4}
            },
            run(5,
                createAssignor(new short[]{0,1,2,3,5}),
                createAssignor(new short[]{0,1,3,4})));

        assertEq(new short[][]{
                {0,1},
                {3,4},
                {2,5}
            },
            run(6,
                createAssignor(new short[]{0,1,2}),
                createAssignor(new short[]{3,4,5}),
                createAssignor(new short[]{0,1,2,3,4,5})));

        assertEq(new short[][]{
                {0,1},
                {3,4},
                {6,7,8},
                {2,5}
            },
            run(9,
                createAssignor(new short[]{0,1,2}),
                createAssignor(new short[]{3,4,5}),
                createAssignor(new short[]{6,7,8}),
                createAssignor(new short[]{0,1,2,3,4,5,6,7,8})));

        assertEq(new short[][]{
                {0,1},
                {2,3}
            },
            run(4,
                createAssignor(new short[]{0,1}),
                createAssignor(new short[]{0,1,2,3})));

        assertEq(new short[][]{
                {0,1},
                {2,3,4,5}
            },
            run(6,
                createAssignor(new short[]{0,1,2}),
                createAssignor(new short[]{0,1,2,3,4,5})));

        assertEq(new short[][]{
                {0,1,2},
                {3,4,5,6,7}
            },
            run(8,
                createAssignor(new short[]{0,1,2,3}),
                createAssignor(new short[]{0,1,2,3,4,5,6,7})));

        assertEq(new short[][]{
                {0,1,2,4},
                {3,5,6,7,8,9}
            },
            run(10,
                createAssignor(new short[]{0,1,2,3,4}),
                createAssignor(new short[]{0,1,2,3,4,5,6,7,8,9})));

        assertEq(new short[][]{
                {0,1,2,3,5},
                {4,6,7,8,9,10,11}
            },
            run(12,
                createAssignor(new short[]{0,1,2,3,4,5}),
                createAssignor(new short[]{0,1,2,3,4,5,6,7,8,9,10,11})));

        assertEq(new short[][]{
                {0,1,2,3,4,6},
                {5,7,8,9,10,11,12,13,14,15}
            },
            run(16,
                createAssignor(new short[]{0,1,2,3,4,5,6,7}),
                createAssignor(new short[]{0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15})));

        assertEq(new short[][]{
                {0,1,2,3,4,5,7,9},
                {6,8,10,11,12,13,14,15,16,17,18,19}
            },
            run(20,
                createAssignor(new short[]{0,1,2,3,4,5,6,7,8,9}),
                createAssignor(new short[]{0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19})));

        assertEq(new short[][]{
                {5,7,9,10,11,12,13,14,15,16,17,18,19},
                {0,1,2,3,4,6,8}
            },
            run(20,
                createAssignor(new short[]{0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19}),
                createAssignor(new short[]{0,1,2,3,4,5,6,7,8,9})));

        assertEq(new short[][]{
                {0,1,2,3,5,7,9,11},
                {4,6,8,10,12,13,14,15,16,17,18,19}
            },
            run(20,
                createAssignor(new short[]{0,1,2,3,4,5,6,7,8,9,10,11,12}),
                createAssignor(new short[]{0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19})));

        assertEq(new short[][]{
                {0,1,2,4,6,8,10,12,14},
                {3,5,7,9,11,13,15,16,17,18,19}
            },
            run(20,
                createAssignor(new short[]{0,1,2,3,4,5,6,7,8,9,10,11,12,13,14}),
                createAssignor(new short[]{0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19})));

        assertEq(new short[][]{
                {0,2,4,6,8,10,12,14,16,18},
                {1,3,5,7,9,11,13,15,17,19}
            },
            run(20,
                createAssignor(new short[]{0,2,4,6,8,10,12,14,16,18}),
                createAssignor(new short[]{0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19})));

        assertEq(new short[][]{
                {0,2,4,6,9},
                {1,3,5,7},
                {8,10,11,12,13,14,15,16,17,18,19}
            },
            run(20,
                createAssignor(new short[]{0,1,2,3,4,5,6,7,8,9}),
                createAssignor(new short[]{0,1,2,3,4,5,6,7,8,9}),
                createAssignor(new short[]{0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19})));

        assertEq(new short[][]{
                {0,1,2,3,5,7,9},
                {10,11,12,13,15,17,19},
                {4,6,8,14,16,18}
            },
            run(20,
                createAssignor(new short[]{0,1,2,3,4,5,6,7,8,9}),
                createAssignor(new short[]{10,11,12,13,14,15,16,17,18,19}),
                createAssignor(new short[]{0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19})));
    }

    static void assertEq(short[][] exp, short[][] act) {
        assertEquals(exp.length, act.length);

        for (int i = 0; i < exp.length; i++) {
            if (!Arrays.equals(exp[i], act[i]))
                fail(Arrays.toString(exp[i]) + " != " + Arrays.toString(act[i]));
        }
    }

    static short[][] run(int parts, AllowedOnlyPartitionAssignor... assignors) {
        return run(parts, parts, assignors);
    }

    static short[][] run(int parts, int expAssignedParts, AllowedOnlyPartitionAssignor... assignors) {
        Map<String,AllowedOnlyPartitionAssignor.Subscription> subs = new HashMap<>();
        Set<AllowedOnlyPartitionAssignor> uniqAssignors = new HashSet<>();
        String name = assignors[0].name();

        for (int i = 0; i < assignors.length; i++) {
            AllowedOnlyPartitionAssignor a = assignors[i];
            assertTrue(uniqAssignors.add(a));
            assertEquals(name, a.name());
            subs.put(String.valueOf(i), new AllowedOnlyPartitionAssignor.Subscription(TOPICS_LIST,
                a.subscriptionUserData(TOPICS_SET)));
        }

        List<PartitionInfo> partsInfo = new ArrayList<>();
        for (int p = 0; p < parts; p++) {
            partsInfo.add(new PartitionInfo(TOPIC_1, p, null, null, null));
            partsInfo.add(new PartitionInfo(TOPIC_2, p, null, null, null));
        }

        Cluster meta = new Cluster("testCluster", Collections.emptySet(), partsInfo,
            Collections.emptySet(), Collections.emptySet());

        Map<String,AllowedOnlyPartitionAssignor.Assignment> assigns = unwrap(
            assignors[0].assign(meta, wrap(subs)));

        short[][] res = new short[assigns.size()][];
        assertEquals(assignors.length, res.length);

        Set<TopicPartition> uniqParts = new HashSet<>();

        for (int i = 0; i < res.length; i++) {
            AllowedOnlyPartitionAssignor.Assignment assign = assigns.get(String.valueOf(i));

            ByteBuffer userData = assign.userData();
            assertTrue(userData == null || 0 == userData.remaining());

            List<TopicPartition> assignedParts = assign.partitions();
            short[] shortAssign = new short[assignedParts.size() / 2]; // divide by 2 because we have 2 topics

            for (int j = 0; j < assignedParts.size(); j += 2) {
                TopicPartition p1 = assignedParts.get(j);
                assertEquals(TOPIC_1, p1.topic());
                assertTrue(uniqParts.add(p1));

                TopicPartition p2 = assignedParts.get(j + 1);
                assertEquals(TOPIC_2, p2.topic());
                assertTrue(uniqParts.add(p2));

                assertEquals(p1.partition(), p2.partition());

                shortAssign[j / 2] = (short)p1.partition();
            }

            res[i] = shortAssign;
        }

        assertEquals(expAssignedParts, uniqParts.size() / 2);

        return res;
    }

    @SuppressWarnings("unchecked")
    static <X> X wrap(Map<String,AllowedOnlyPartitionAssignor.Subscription> subs) {
        if (GRP_SUB_CONSTRUCTOR == null)
            return (X)subs;

        try {
            return (X)GRP_SUB_CONSTRUCTOR.newInstance(subs);
        }
        catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    static <X> X unwrap(Object subs) {
        if (GRP_SUB_UNWRAPPER == null)
            return (X)subs;

        try {
            return (X)GRP_SUB_UNWRAPPER.invoke(subs);
        }
        catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static AllowedOnlyPartitionAssignor createAssignor(Object allowedParts) {
        AllowedOnlyPartitionAssignor assignor = new AllowedOnlyPartitionAssignor();

        Map<String,Object> cfg = new HashMap<>();
        cfg.put(AllowedOnlyPartitionAssignor.ALLOWED_PARTS, allowedParts);
        assignor.configure(cfg);

        return assignor;
    }
}