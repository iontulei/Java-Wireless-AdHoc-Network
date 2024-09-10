/**
 * Group Java 1
 * 
 * Rein Fernhout, s2990083 
 * Stan Groote Stroek, s3003736 
 * Ion Tulei, s2928787 
 * Stefan Veltmaat, s2989999 
 */

import client.*;

import java.nio.ByteBuffer;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class MyProtocol {

    // The host to connect to. Set this to localhost when using the audio interface tool.
    private static final String SERVER_IP = "netsys.ewi.utwente.nl"; //"127.0.0.1";
    // The port to connect to. 8954 for the simulation server.
    private static final int SERVER_PORT = 8954;
    // The frequency to use.
    private static int frequency = 600;
    // View the simulator at https://netsys.ewi.utwente.nl/integrationproject/

    /**
     * List of temporary IDs used by nodes
     */
    private final ArrayList<Integer> nodesTemp;
    private final BlockingQueue<Message> ackSendingQueue;
    private final BlockingQueue<Message> dataShortSendingQueue;
    private final BlockingQueue<Message> dataSendingQueue;
    private final Map<Integer, Route> routingTable = new ConcurrentHashMap<>();
    private boolean isChannelFree = true;

    /**
     * The ID of this node.
     */
    private int id = -1;

    /**
     * Temporary ID for this node (between 1 and 2^12)
     */
    private final int temporaryID;

    /**
     * Time to live for routes in ms.
     * This should be fairly high to prevent timeouts when network is under load
     */
    private static final int ROUTING_TTL = 15000;

    /**
     * Time in between the sending of routing updates
     */
    private static final long ROUTING_TASK_PERIOD = 3000;

    /**
     * localSeq uses 6 bits => max value is 63
     */
    private int localSeq = 0;
    private static final long ACKNOWLEDGEMENT_TASK_PERIOD = 3000;
    private static final int ACKNOWLEDGEMENT_TTL = 30000;   // ttl for an ack packet (30s is good)
    private static final int LOST_ACK_LIMIT = 12;   // after how many lost acks to give up on the message

    /**
     * The key in the sentSequencesTTL and sentMessages will be a combination of:
     * - 2 destination bits (d)
     * - 6 sequence bits (s).
     * - Example: (dd ss ss ss).
     * The sentSequencesTTL map keeps track of sent unacknowledged packets.
     */
    private final Map<Integer, Integer> sentSequencesTTL = new ConcurrentHashMap<>();
    private final Map<Integer, Message> sentMessages = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> lostAcknowledgements = new ConcurrentHashMap<>();

    /**
     * Maps for receiving messages.
     */
    private final Map<Integer, ArrayList<Integer>> receivedSequences = new ConcurrentHashMap<>();
    private final Map<Integer, Map<Integer, Packet>> receivedPackets = new ConcurrentHashMap<>();

    /**
     * When did the discovery start
     */
    private long DISCOVERY_START_TIME;

    /**
     * How long this node will be in DISOVERY mode for. Collecting temporary ID's before settling for a permanent one.
     */
    private static final int DISCOVERY_PERIOD = 30000;
    private static final int DISCOVERY_TASK_PERIOD = 12000;

    /**
     * How many nodes this network supports.
     */
    private static final int MAX_NODES = 4;

    /**
     * String constants.
     */
    private static final String ID = "ID";
    private static final String ROUTE = "ROUTE";
    private static final String ACKNOWLEDGEMENT = "ACKNOWLEDGEMENT";

    public MyProtocol(String serverIp, int serverPort, int frequency) {

        // Setup the receive and send queues
        BlockingQueue<Message> receivedQueue = new LinkedBlockingQueue<>();
        BlockingQueue<Message> sendingQueue = new LinkedBlockingQueue<>();
        ackSendingQueue = new LinkedBlockingQueue<>();
        dataShortSendingQueue = new LinkedBlockingQueue<>();
        dataSendingQueue = new LinkedBlockingQueue<>();

        DISCOVERY_START_TIME = System.currentTimeMillis();

        temporaryID = getRandomNumber(1, 4095);
        nodesTemp = new ArrayList<>();
        nodesTemp.add(temporaryID);

        // Give the client the Queues to use
        new Client(serverIp, serverPort, frequency, receivedQueue, sendingQueue);

        //System.out.println("Sending temporary ID: " + temporaryID);
        broadcastDataShort(temporaryID);

        // Start thread to handle received messages
        new receiveThread(receivedQueue).start();
        new sendThread(sendingQueue).start();

        new Timer("Routing Timer").schedule(routingTask, 0, ROUTING_TASK_PERIOD);
        new Timer("Acknowledgement Timer").schedule(acknowledgementTask, 0, ACKNOWLEDGEMENT_TASK_PERIOD);
        new Timer("Discovery Timer").schedule(discoveryTask, 0, DISCOVERY_TASK_PERIOD);

        System.out.println("Initializing...");

        // handle sending from stdin from this thread.
        handleInput();
    }

    /**
     * This task sends the routing table every {@link MyProtocol#ROUTING_TASK_PERIOD}.
     */
    TimerTask routingTask = new TimerTask() {
        public void run() {
            if (id != -1) {
                // Subtract this timer delay from the TTL of the routes
                subtractTTLFromRoutes(ROUTING_TASK_PERIOD);
            }
        }

        /**
         * Subtract time from the TTL of all routes in the table.
         * And delete any timed out routes.
         * @param time (in ms)
         */
        private synchronized void subtractTTLFromRoutes(long time) {
            for (var entry : routingTable.entrySet()) {
                Route r = entry.getValue();
                r.ttl -= time;

                if (r.ttl <= 0) {
                    //System.out.println("TTL EXPIRED FOR DESTINATION = " + entry.getKey());
                    routingTable.remove(entry.getKey());
                }
            }
        }
    };

    TimerTask discoveryTask = new TimerTask() {
        public void run() {
            /* Broadcast temporary id once in a while to trigger discovery phase. */
            broadcastDataShort(temporaryID);
        }
    };

    TimerTask acknowledgementTask = new TimerTask() {
        public void run() {
            subtractTTLFromAcknowledgements(ACKNOWLEDGEMENT_TASK_PERIOD);
        }

        private synchronized void subtractTTLFromAcknowledgements(long time) {
            if (sentSequencesTTL.size() > 0) {
                //System.out.println("Acknowledgement table ID = " + id + ", SIZE = " + sentSequencesTTL.size());
            }

            for (var entry : sentSequencesTTL.entrySet()) {
                if (Objects.isNull(entry.getValue())) {
                    return;
                }

                int ttl = entry.getValue();
                ttl -= time;
                entry.setValue(ttl);

                //System.out.println("ID = " + ((entry.getKey() >>> 6) & 0b11) + ", SEQ = " + (entry.getKey() & 0b111111) + ", TTL = " + entry.getValue());

                if (ttl <= 0) {
                    //System.out.println("ACKNOWLEDGEMENT TTL EXPIRED FOR SEQUENCE = " + ((entry.getKey() >>> 6) & 0b11));
                    try {
                        Message m = sentMessages.get(entry.getKey());
                        if (Objects.isNull(m)) {
                            return;
                        }
                        Packet pkt = new Packet(m.getData());
                        int seqKey = ((pkt.dst & 0b11) << 6 | (pkt.seq & 0b111111));

                        /* Increase the number of how many timeouts expired for acknowledgements. */
                        int lostCount = 0;
                        if (lostAcknowledgements.containsKey(seqKey)) {
                            lostCount = lostAcknowledgements.get(seqKey);
                        }
                        lostCount++;
                        lostAcknowledgements.put(seqKey, lostCount);
                        //System.out.println("LOST COUNT = " + lostCount);

                        /* Assume the node that has to send the ack is unreachable. */
                        if (lostCount >= LOST_ACK_LIMIT) {
                            //System.out.println("Giving up on ack from SRC = " + (entry.getKey() >>> 6) + ", SEQ = " + (entry.getKey() & 0b111111));
                            System.out.println("Failed to transmit to " + (entry.getKey() >>> 6));

                            lostAcknowledgements.remove(seqKey);
                            sentMessages.remove(seqKey);
                            /* This should be last. */
                            sentSequencesTTL.remove(seqKey);
                            return;
                        }

                        /* Update the next hop of the packet. */
                        if (routingTable.containsKey(pkt.dst)) {
                            pkt.nxt = routingTable.get(pkt.dst).nextHop;
                        }
                        /* If we didn't find the dst in routing table, just resend the initial packet. */
                        dataSendingQueue.put(pkt.toMessage());
                        sentSequencesTTL.put(entry.getKey(), ACKNOWLEDGEMENT_TTL);
                        sentMessages.put(entry.getKey(), pkt.toMessage());
                    } catch (InterruptedException e) {
                        System.exit(2);
                    }
                }
            }
        }
    };

    /**
     * Handle console input
     */
    private void handleInput() {
        try {
            ByteBuffer temp = ByteBuffer.allocate(1024);
            int read = 0;
            int newLineOffset = 0;
            while (true) {
                read = System.in.read(temp.array()); // Get data from stdin, hit enter to send!
                if (read > 0) {
                    if (temp.get(read - 1) == '\n' || temp.get(read - 1) == '\r')
                        newLineOffset = 1; //Check if last char is a return or newline so we can strip it
                    if (read > 1 && (temp.get(read - 2) == '\n' || temp.get(read - 2) == '\r'))
                        newLineOffset = 2; //Check if second to last char is a return or newline so we can strip it

                    /* Process command. */
                    String input = new String(temp.array(), 0, read - newLineOffset).trim();

                    if (id == -1) {
                        System.out.println("Cant' transmit yet.");
                        continue;
                    }

                    if (input.startsWith("/all ")) {
                        String message = input.substring(5);
                        //System.out.println("/all: " + message);
                        sendAllCommand(message);
                    } else if (input.startsWith("/whisper ")) {
                        String[] parts = input.substring(9).split("\\s+", 2);
                        if (parts.length == 2) {
                            try {
                                int receiver = Integer.parseInt(parts[0]);
                                String message = parts[1];
                                //System.out.println("Receiver: " + receiver + " Whisper: " + message);
                                whisperCommand(receiver, message);
                            } catch (NumberFormatException e) {
                                System.out.println("Invalid receiver: " + parts[0]);
                            }
                        } else {
                            System.out.println("Invalid whisper command");
                        }
                    } else if (input.startsWith("/online")) {
                        onlineCommand();
                    } else {
                        System.out.println("Invalid command: " + input);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(2);
        }
    }

    private void sendAllCommand(String text) throws InterruptedException {
        ByteBuffer toSend = ByteBuffer.allocate(text.length());
        toSend.put(text.getBytes());

        if (toSend.limit() > 1024) {
            System.out.println("Message exceeds maximum length. Discarding.");
            return;
        }
        if (toSend.limit() <= 0) {
            System.out.println("Message is empty. Discarding.");
            return;
        }

        System.out.print("Broadcast sent to ");
        for (var entry : routingTable.entrySet()) {
            System.out.print(entry.getKey() + ", ");
        }
        System.out.println("message: " + text);

        /* Send all is a broadcast. */
        ByteBuffer[] segments = segmentMessage(toSend);
        List<Packet> packets = createPackets(segments, true, 0, 0);

        for (Packet pkt : packets) {
            Message msg = pkt.toMessage();

            // if broadcast, add an ack timer for each currently known node
            //System.out.println("Sending " + pkt);
            dataSendingQueue.put(msg);

            // remove the broadcast flag, to create whisper ack timeouts
            pkt.flg &= 0b1101;
            for (var entry : routingTable.entrySet()) {
                pkt.dst = entry.getKey();
                pkt.nxt = entry.getValue().nextHop;

                int seqKey = ((pkt.dst & 0b11) << 6 | (pkt.seq & 0b111111));

                /* Initiation of ack timeouts was moved to the sendThread */
                // sentSequencesTTL.put(seqKey, ACKNOWLEDGEMENT_TTL);
                sentMessages.put(seqKey, pkt.toMessage());

                //System.out.println("Created timeout for DST = " + pkt.dst);
            }
        }
    }

    private void whisperCommand(int dst, String text) throws InterruptedException {
        ByteBuffer toSend = ByteBuffer.allocate(text.length());
        toSend.put(text.getBytes());

        if (toSend.limit() > 1024) {
            System.out.println("Message exceeds maximum length. Discarding.");
            return;
        }
        if (toSend.limit() <= 0) {
            System.out.println("Message is empty. Discarding.");
            return;
        }

        if (dst == id) {
            System.out.println("Unable to send whisper to yourself.");
            return;
        }

        if (!routingTable.containsKey(dst)) {
            System.out.println("Destination " + dst + " is not online.");
            return;
        }

        System.out.println("Whisper sent to " + dst + ": " + text);

        /* Whisper is not a broadcast. */
        ByteBuffer[] segments = segmentMessage(toSend);
        List<Packet> packets = createPackets(segments, false, dst, routingTable.get(dst).nextHop);
        for (Packet pkt : packets) {
            Message msg = pkt.toMessage();

            //System.out.println("Sending " + pkt);
            dataSendingQueue.put(msg);

            int seqKey = ((pkt.dst & 0b11) << 6 | (pkt.seq & 0b111111));
            /* Ack timout is initiated in the sendThread. */
            sentMessages.put(seqKey, pkt.toMessage());
        }
    }

    private void onlineCommand(){
        if (routingTable.isEmpty()) {
            System.out.println("No nodes are online.");
            return;
        }

        System.out.println("Currently online:");
        for (var entry : routingTable.entrySet()) {
            System.out.println("- node " + entry.getKey());
        }
    }

    public static void main (String[]args){
        if (args.length > 0) {
            frequency = Integer.parseInt(args[0]);
        }
        new MyProtocol(SERVER_IP, SERVER_PORT, frequency);
    }

    private class sendThread extends Thread {
        private final BlockingQueue<Message> sendingQueue;
        private final Lock lock = new ReentrantLock();
        private boolean routingTableSent = false;
        private boolean dataPacketSent = false;

        public sendThread(BlockingQueue<Message> sendingQueue) {
            super();
            this.sendingQueue = sendingQueue;
        }

        @Override
        public void run() {
            while (true) {
                lock.lock();
                try {
                    /* Send packets during the id discovery phase. */
                    if (isChannelFree && id == -1) {
                        Thread.sleep((long) (Math.random() * 1000));
                        if (!dataShortSendingQueue.isEmpty() && isChannelFree) {
                            sendingQueue.put(dataShortSendingQueue.take());
                        }
                    }

                    /* Calculate whose turn it is based on the global clock. (round-robin implementation) */
                    int totalSlots = 20;
                    long oneSlot = totalSlots / MAX_NODES;
                    long currentTime = (System.currentTimeMillis() / 500) % totalSlots;
                    boolean myTurn = currentTime == oneSlot * id || currentTime == oneSlot * id + 1;

                    if (isChannelFree && id != -1 && myTurn) {
                        if (!routingTableSent) {
                            sendingQueue.put(getRoutingTable());
                            routingTableSent = true;
                        }
                        if (!dataShortSendingQueue.isEmpty()) {
                            sendingQueue.put(dataShortSendingQueue.take());
                        }

                        if (!ackSendingQueue.isEmpty()) {
                            sendingQueue.put(ackSendingQueue.take());
                        } else if (!dataSendingQueue.isEmpty() && sentSequencesTTL.size() < 30 && !dataPacketSent) {
                            /* SWS = 29 */

                            /* Put the packet into the sending queue. */
                            Message m = dataSendingQueue.take();
                            Packet pkt = new Packet(m.getData());
                            //System.out.println("Transmitting: " + pkt);
                            sendingQueue.put(m);
                            dataPacketSent = true;

                            /* Create the ack timer for each DATA packet if you are the source. */
                            if (pkt.src == id) {
                                /* If broadcast, create n (n = routingTable.size()) acknowledgements for n destinations. */
                                if ((pkt.flg & 0b0010) == 0b0010) {
                                    for (var entry : routingTable.entrySet()) {
                                        int dst = entry.getKey();
                                        int seqKey = ((dst & 0b11) << 6 | (pkt.seq & 0b111111));
                                        sentSequencesTTL.put(seqKey, ACKNOWLEDGEMENT_TTL);
                                    }
                                } else {
                                    /* Create ack timeout for whisper. */
                                    int seqKey = ((pkt.dst & 0b11) << 6 | (pkt.seq & 0b111111));
                                    sentSequencesTTL.put(seqKey, ACKNOWLEDGEMENT_TTL);
                                }
                            }
                        }
                    } else if (!myTurn) {
                        routingTableSent = false;
                        dataPacketSent = false;
                    }

                    boolean discoveryTimedOut = System.currentTimeMillis() - DISCOVERY_START_TIME >= DISCOVERY_PERIOD;

                    /* Assign the new id. (0 or 1 or 2 or 3) */
                    if ((nodesTemp.size() >= MAX_NODES || discoveryTimedOut) && id == -1) {
                        if (discoveryTimedOut) {
                            System.out.println("Disovery timeout, settling for " + nodesTemp.size() + " nodes");
                        }
                        Collections.sort(nodesTemp);
                        id = nodesTemp.indexOf(temporaryID);
                        System.out.println("My ID: " + id);
                        for (var id : nodesTemp) {
                            //System.out.print(id + ", ");
                        }
                        //System.out.println("");
                    }
                } catch (InterruptedException e) {
                    System.exit(2);
                }

                lock.unlock();
            }
        }
    }

    private class receiveThread extends Thread {
        private final BlockingQueue<Message> receivedQueue;
        private final Lock lock = new ReentrantLock();

        public receiveThread(BlockingQueue<Message> receivedQueue) {
            super();
            this.receivedQueue = receivedQueue;
        }

        @Override
        public void run() {
            while (true) {
                lock.lock();
                try {
                    Message m = receivedQueue.take();
                    switch (m.getType()) {
                        case BUSY:
                            isChannelFree = false;
                            break;
                        case FREE:
                            isChannelFree = true;
                            break;
                        case DATA:
                            dataReceived(m);
                            break;
                        case DATA_SHORT:
                            dataShortReceived(m);
                            break;
                        case END:
                            System.exit(0);
                            break;
                        default:

                    }
                } catch (InterruptedException e) {
                    //System.out.println("Failed to take from queue: " + e);
                }
                lock.unlock();
            }
        }

        /**
         * A data packet consists of
         * src - 2 bits
         * dst - 2 bits
         * flg - 4 bits
         * nxt - 2 bits
         * seq - 6 bits
         * @param m the received message
         * @throws InterruptedException
         */
        private void dataReceived(Message m) throws InterruptedException {
            ByteBuffer receivedData = m.getData();
            Packet pkt = new Packet(receivedData);

            //System.out.print("Received " + pkt);

            if (pkt.src == id) {
                //System.out.println("Ignore message from myself.");
                return;
            }

            /* Broadcast received. */
            if ((pkt.flg & 0b0010) == 0b0010) {
                if (receivedSequences.containsKey(pkt.src)) {
                    if (receivedSequences.get(pkt.src).contains(pkt.seq)) {
                        //System.out.println("Already saw this broadcast from SRC = " + pkt.src + ", with SEQ = " + pkt.seq);
                        return;
                    } else {
                        /* Accept broadcast. */
                        ArrayList<Integer> arr = receivedSequences.get(pkt.src);
                        arr.add(pkt.seq);

                        receivedSequences.put(pkt.src, arr);
                        receivedPackets.get(pkt.src).put(pkt.seq, pkt);
                    }
                } else {
                    /* Accept broadcast */
                    ArrayList<Integer> arr = new ArrayList<>();
                    Map<Integer, Packet> map = new ConcurrentHashMap<>();

                    arr.add(pkt.seq);
                    map.put(pkt.seq, pkt);

                    receivedSequences.put(pkt.src, arr);
                    receivedPackets.put(pkt.src, map);
                }

                /* Retransmit broadcast onward */
                //System.out.println("Relaying broadcast: " + pkt);
                dataSendingQueue.put(m);

                /* Send ack for broadcast. */
                if (routingTable.containsKey(pkt.src)) {
                    ackSendingQueue.put(createAck(id, pkt.src, routingTable.get(pkt.src).nextHop, pkt.seq));
                    //System.out.println("ACK sent for broadcast from SRC = " + pkt.src);
                }

                /* Perform RWS = 35 check. */
                /* We perform only one if instead of a while, because we receive one message at a time. */
                if (receivedSequences.size() > 35) {
                    int key = receivedSequences.get(pkt.src).get(0);
                    receivedSequences.get(pkt.src).remove(0);
                    receivedPackets.get(pkt.src).remove(key);
                }

                /* Check for fragmentation. */
                checkFragmentationAndDisplay(pkt);
                return;
            }

            /* Whisper received. */
            if (pkt.dst == id) {

                /* Send ack no matter if it's a first msg or retransmission. */
                if (routingTable.containsKey(pkt.src)) {
                    ackSendingQueue.put(createAck(id, pkt.src, routingTable.get(pkt.src).nextHop, pkt.seq));
                    //System.out.println("Sent ack to DST = " + pkt.dst + ", for SEQ = " + pkt.seq);
                }

                if (!receivedSequences.containsKey(pkt.src)) {
                    ArrayList<Integer> arr = new ArrayList<>();
                    Map<Integer, Packet> map = new ConcurrentHashMap<>();

                    arr.add(pkt.seq);
                    map.put(pkt.seq, pkt);

                    receivedSequences.put(pkt.src, arr);
                    receivedPackets.put(pkt.src, map);
                } else {
                    ArrayList<Integer> oldSeq = receivedSequences.get(pkt.src);
                    if (!oldSeq.contains(pkt.seq)) {
                        oldSeq.add(pkt.seq);
                        receivedSequences.put(pkt.src, oldSeq);
                        receivedPackets.get(pkt.src).put(pkt.seq, pkt);
                        checkFragmentationAndDisplay(pkt);
                    }
                }
            }

            /* Relay message. Send only if you are the nexthop id. And set the new nexthop value. */
            if (pkt.nxt == id && pkt.dst != id && routingTable.containsKey(pkt.dst)) {
                pkt.nxt = routingTable.get(pkt.dst).nextHop;
                dataSendingQueue.put(pkt.toMessage());
                //System.out.println("Relaying " + pkt);
            }
        }

        private void checkFragmentationAndDisplay(Packet pkt) {
            boolean broadcast = (pkt.flg & 0b0010) == 0b0010;

            // not a fragment
            if ((pkt.flg & 0b1100) == 0b1100) {
                System.out.println((broadcast ? "Broadcast" : "Whisper") + " from " + pkt.src + ": " + pkt.dat);
            } else {
                /* Fragmented message */

                // search backwards for starting fragment
                // if a seq is missing before you find start, just do nothing and return
                // if start is found, store seq
                int startingSeq = pkt.seq;
                while (true) {
                    if (!receivedPackets.get(pkt.src).containsKey(startingSeq)) {
                        return;
                    }
                    if ((receivedPackets.get(pkt.src).get(startingSeq).flg & 0b1000) == 0b1000) {
                        break;
                    }
                    startingSeq--;
                }

                // search forward for ending fragment
                // if a seq is missing before you find end, just do nothing and return
                // if end is found, store seq
                int endingSeq = pkt.seq;
                while (true) {
                    if (!receivedPackets.get(pkt.src).containsKey(endingSeq)) {
                        return;
                    }
                    if ((receivedPackets.get(pkt.src).get(endingSeq).flg & 0b0100) == 0b0100) {
                        break;
                    }
                    endingSeq++;
                }

                // if start and end are found, print everything from start until end
                System.out.print((broadcast ? "Broadcast" : "Whisper") + " from " + pkt.src + ": ");
                for (int i = startingSeq; i <= endingSeq; i++) {
                    System.out.print(receivedPackets.get(pkt.src).get(i).dat);
                }
                System.out.println();
            }
        }

        private Message createAck(int src, int dst, int next, int seq) {
            ByteBuffer message = ByteBuffer.allocate(2);
            byte firstByte = (byte) ((0b1111 << 4) | ((src & 0b11) << 2) | (dst & 0b11));
            message.put(firstByte);
            byte secondByte = (byte) (((next & 0b11) << 6) | (seq & 0b111111));
            message.put(secondByte);

            return new Message(MessageType.DATA_SHORT, message);
        }

        private void dataShortReceived(Message m) {
            ByteBuffer receivedData = m.getData();
            // //System.out.println(getDataShortType(receivedData));

            switch (getDataShortType(receivedData)) {
                case ID:
//                    //System.out.println("----- ID RECEIVED -----");
                    handleReceivedID(receivedData);
                    break;
                case ROUTE:
//                    //System.out.println("----- ROUTE RECEIVED -----");
                    doRouting(receivedData);
                    break;
                case ACKNOWLEDGEMENT:
//                    //System.out.println("----- ACK RECEIVED -----");
                    handleAcknowledgement(receivedData);
                    break;
            }
        }

        private void handleAcknowledgement(ByteBuffer receivedData) {
            int src = (receivedData.get(0) >>> 2) & 0b11;
            int dst = receivedData.get(0) & 0b11;
            int nxt = (receivedData.get(1) >>> 6) & 0b11;
            int seq = receivedData.get(1) & 0b111111;

            if (src == id) {
                return;
            }

            /* Retransmit ack. */
            if (nxt == id) {
                if (routingTable.containsKey(dst)) {
                    try {
                        ackSendingQueue.put(createAck(src, dst, routingTable.get(dst).nextHop, seq));
                    } catch (InterruptedException e) {
                        System.exit(2);
                    }
                }
            }

            /* Accept ack. */
            if (dst == id) {
                int seqKey = ((src & 0b11) << 6) | (seq & 0b111111);
                if (sentSequencesTTL.containsKey(seqKey)) {
                    /* !!! Be careful with seqKey and seq. */
                    sentSequencesTTL.remove(seqKey);
                    sentMessages.remove(seqKey);
                    lostAcknowledgements.remove(seqKey);
                    //System.out.println("Accepted ack from SRC = " + src + ", for SEQ = " + seq);
                }
            }
        }

        private void handleReceivedID(ByteBuffer receivedData) {
            int receivedID = convertByteSumToInt(receivedData, 0, 2);
            //(nodesTemp.size() >= MAX_NODES || discoveryTimedOut) && id == -1

            boolean discoveryTimedOut = System.currentTimeMillis() - DISCOVERY_START_TIME >= DISCOVERY_PERIOD;

            //System.out.println("Received ID: " + receivedID);

            // restart discovery if it has finished a while ago
            if (id != -1 && discoveryTimedOut && !nodesTemp.contains(receivedID)) {

                System.out.println("Restarting the discovery period");

                id = -1;
                DISCOVERY_START_TIME = System.currentTimeMillis();

                nodesTemp.clear();
                nodesTemp.add(temporaryID);
                nodesTemp.add(receivedID);

                /* Clear all tables / maps related to routing and messages. */
                routingTable.clear();
                ackSendingQueue.clear();
                dataShortSendingQueue.clear();
                dataSendingQueue.clear();

                sentSequencesTTL.clear();
                sentMessages.clear();
                lostAcknowledgements.clear();

                receivedSequences.clear();
                receivedPackets.clear();

                for (var node : nodesTemp) {
                    broadcastDataShort(node);
                }

            } else {
                if (!nodesTemp.contains(receivedID)) {
                    nodesTemp.add(receivedID);
                    for (var node : nodesTemp) {
                        broadcastDataShort(node);
                    }
                }
            }
        }

        private String getDataShortType(ByteBuffer receivedData) {

            int data = convertByteSumToInt(receivedData, 0, 2);

            if (data >= 1 && data <= 4095) {
                return ID;
            } else if (isRoutingPacket(receivedData)) {
                return ROUTE;
            }
            return ACKNOWLEDGEMENT;
        }

        private boolean isRoutingPacket(ByteBuffer receivedData) {
            int dataByte = receivedData.get(0);
            return ((dataByte & 0b11) == ((dataByte >> 2) & 0b11));
        }

        private void doRouting(ByteBuffer receivedData) {
            int data = convertByteSumToInt(receivedData, 0, 2);

            int neighbour = (data >>> 14) & 0b11;
            int routes = (data >>> 12) & 0b11;

            if (neighbour == id) {
                return;
            }

            Route r = new Route(neighbour, 0, ROUTING_TTL);
            routingTable.put(neighbour, r);

            for (int i = 0; i < routes; i++) {
                int dest = (data >>> (10 - i * 4)) & 0b11;
                int next = (data >>> (8 - i * 4)) & 0b11;
                int cost = (dest == neighbour) ? 0 : 1;

                if (dest == id || next == id) {
                    continue;
                }

                // There is no route to this dest yet
                if (!routingTable.containsKey(dest)) {
                    r = new Route(neighbour, cost, ROUTING_TTL);
                    routingTable.put(dest, r);
                } else {
                    Route oldR = routingTable.get(dest);

                    // This route is known (in which case the TTL and possibly the cost should be renewed)
                    // or this route has a better cost
                    if (oldR.nextHop == neighbour || oldR.cost > cost) {
                        r = new Route(neighbour, cost, ROUTING_TTL);
                        routingTable.put(dest, r);
                    }
                }
            }
        }
    }

    public Message getRoutingTable () {
        // add routing table info to toSend
        int toSend = 0;
        int size = routingTable.size();

        toSend |= (id & 0b11) << 14;
        toSend |= (size & 0b11) << 12;

        ArrayList<Integer> destinations = new ArrayList<>(routingTable.keySet());
        ArrayList<Route> routes = new ArrayList<>(routingTable.values());

        /*
         * The fact that every routing list contains at least one direct route is used
         * for disambiguating DATA_SHORTS. So here to list is sorted to start with direct routes.
         */
        for (int i = 0; i < size - 1; i++) {
            for (int j = 0; j < size - i - 1; j++) {
                if (routes.get(j).cost > routes.get(j + 1).cost) {
                    // swap destinations[j] and destinations[j+1]
                    int temp1 = destinations.get(j);
                    destinations.set(j, destinations.get(j + 1));
                    destinations.set(j + 1, temp1);

                    // swap routes[j] and routes[j+1]
                    Route temp2 = routes.get(j);
                    routes.set(j, routes.get(j + 1));
                    routes.set(j + 1, temp2);
                }
            }
        }

        //System.out.println("\nRouting table created. ID = " + id + " Size = " + size);

        for (int i = 0; i < size; i++) {
            int dest = destinations.get(i);
            int next = routes.get(i).nextHop;

            toSend |= (dest & 0b11) << (10 - i * 4);
            toSend |= (next & 0b11) << (8 - i * 4);

            //System.out.println("Dest = " + dest + ", Next = " + next + ", Cost = " + routes.get(i).cost + ", TTL = " + routes.get(i).ttl);
        }

        //System.out.println();

        // no need to pad toSend with zeros because toSend was initially 0, so it is already padded with 0 bitsW
        ByteBuffer buffer = ByteBuffer.allocate(2);
        buffer.put((byte) (toSend >>> 8));
        buffer.put((byte) toSend);
        return (new Message(MessageType.DATA_SHORT, buffer));
    }

    /**
     * Generate a random value between min and max (inclusive).
     * @param min the minimum value (inclusive)
     * @param max the maximum value (inclusive)
     * @return a random value in the range between min and max (inclusive)
     */
    public int getRandomNumber ( int min, int max){
        return (int) ((Math.random() * (max - min + 1)) + min);
    }

    /**
     * Broadcast a 2 byte data short.
     * According to some guy on discord it takes about 200ms to send.
     */
    public void broadcastDataShort ( int data){
        try {
            ByteBuffer buffer = ByteBuffer.allocate(2);
            buffer.put((byte) (data >>> 8));
            buffer.put((byte) data);
//                dataSendingQueue.put(new Message(MessageType.DATA_SHORT, buffer));
            dataShortSendingQueue.put(new Message(MessageType.DATA_SHORT, buffer));
        } catch (InterruptedException e) {
            System.exit(2);
        }
    }

    /**
     * converts the specified bytes into an integer.
     * the range should not be larger than 4 as it won't fit into the integer
     * @param bytes the ByteArray to be used
     * @param start the start of the range of bytes (inclusive)
     * @param end the end of the range of bytes (exclusive)
     * @return the number equal to value of the specified bytes
     */
    public int convertByteSumToInt (ByteBuffer bytes,int start, int end){
        int value = 0;
        for (int i = 0; i < (end - start); i++) {
            value += ((bytes.get(i + start) & 0xFF) << (((end - start - 1) * 8) - (8 * i)));
        }
        return value;
    }

    /**
     * divides a given byte array into segments of 30 characters each.
     * @param message the message to be segmented
     * @return an array of byte buffers
     */
    public ByteBuffer[] segmentMessage (ByteBuffer message){
        List<ByteBuffer> messageSegments = new ArrayList<>();

        int i = 0;
        while (i < message.limit()) {
            ByteBuffer segment = ByteBuffer.allocate(30);
//            System.arraycopy(message.array(),i,segment,0,Math.min(30,message.limit() - i));
            for (int j = i; j < i + Math.min(30, message.limit() - i); j++) {
                segment.put(message.get(j));
            }
            messageSegments.add(segment);
            i += 30;
        }
        while (messageSegments.get(messageSegments.size() - 1).hasRemaining()) {
            messageSegments.get(messageSegments.size() - 1).put((byte) 0);
        }
        return messageSegments.toArray(new ByteBuffer[messageSegments.size()]);
    }

    /**
     * creates an array of packets from a given array of message segments
     * @param segments an array of message segments
     * @param isBroadcast true if the packets are to be broadcasted, false if they are to be whispered
     * @param dst the destination of the packets
     * @param nextHop the next hop of the packets
     * @return an array of packets
     */
    public List<Packet> createPackets (ByteBuffer[]segments,boolean isBroadcast, int dst, int nextHop){
        List<Packet> packets = new ArrayList<>();
        for (int i = 0; i < segments.length; i++) {
            int flags = 0;
            if (i == 0) {
                flags = flags | 0b1000;
            } // first fragment flag
            if (i == segments.length - 1) {
                flags = flags | 0b0100;
            } // final fragment flag
            if (isBroadcast) {
                flags = flags | 0b0010;
            } // broadcast flag
            Packet packet = new Packet(id, dst, flags, nextHop, localSeq, segments[i]);
            packets.add(packet);
            localSeq++;
            if (localSeq > 63) {
                localSeq = 0;
            }
        }
        return packets;
    }
}
