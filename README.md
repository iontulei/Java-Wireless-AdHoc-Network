# Java - Network System Integration Project

**Module 3, Bachelor of Technical Computer Science, University of Twente**

**Date:** 20-04-2023

**Local Integration Emulator:** `netsys.ewi.utwente.nl/integrationproject`

## 1. System Overview
The implemented system is a wireless ad-hoc network that utilizes dynamic addressing, medium access control, reliable broadcasting, and uni/multicasting, multi-hop forwarding, and fragmentation functionalities. The system consists of multiple nodes that can communicate with each other without any centralized infrastructure. The nodes use a turn-based protocol to regulate medium access, and each node is assigned a unique identifier that is used for reliable broadcasting and multi-hop forwarding.

## 2. System Functionalities

### 2.1 Medium Access Control
During the “discovery phase,” all nodes send their IDs at random intervals. After this phase, the nodes use a turn-based protocol. Each node is allowed to send data within a specific time window (1000 ms). Between these windows, there are breaks (1500 ms) to ensure a full data packet has enough time to finish sending before another node’s window starts.

### 2.2 Reliable Broadcasting

#### 2.2.1 Broadcast
When a node broadcasts a message, it will create an acknowledgment timeout for each receiving node. If an acknowledgment is not received within the `ACKNOWLEDGEMENT_TTL`, the message is resent as a whisper to the node that hasn't acknowledged the broadcast.

#### 2.2.2 Whisper
A node sending a whisper will create an acknowledgment timeout for the packet. If no acknowledgment is received within the `ACKNOWLEDGEMENT_TTL`, the whisper is resent, and the timeout is reset. After 12 failed retransmissions, the node reports that the message failed to arrive.

#### 2.2.3 Sequence Wrapping
Sequence numbers are 6 bits, allowing a maximum of 64 different sequence numbers. To differentiate messages from different nodes, each node tracks received messages associated with the sender's ID. The following window sizes were chosen: `SWS = 29`, `RWS = 35`, ensuring delayed packets are not treated as retransmissions.

### 2.3 Multi-Hop Forwarding

#### 2.3.1 Broadcast
A node receiving a broadcast will forward it unless it has seen the specific broadcast before.

#### 2.3.2 Whisper
When sending a whisper, the node sets the `NextHop` of the packet according to its routing table. If a node receives a whisper with its address as `NextHop`, it forwards the packet to the next hop in the routing table.

### 2.4 Dynamic Addressing
Nodes start in a “discovery phase,” broadcasting a randomized 12-bit `TID` (Temporary Identifier) at random intervals. The discovery phase ends when either 4 TIDs are collected or 30 seconds have passed. Nodes then use their index in the sorted TID list as a permanent ID. If a new TID is discovered, all nodes re-enter the discovery phase, and the entire network state is reset.

### 2.5 Fragmentation
Messages too long to fit in a single packet are fragmented into 30-byte packets. Flags indicate the start and end of a message. Sequence numbers are used to reconstruct the message in the correct order.

### 2.6 Routing
The system uses a Distance-vector routing protocol. The routing table fits in a single `DATA_SHORT` packet, allowing frequent updates. Entries in the routing table have a `TTL` period, after which they are removed. Direct routes are prioritized over indirect routes.

### 2.7 TUI
A simple TUI was implemented with the following commands:
- `/all “message”` - Broadcast a message to all reachable nodes.
- `/whisper “id” “message”` - Whisper a message to a specified node.
- `/online` - Display all currently reachable nodes.

## 3. Protocols

### 3.1 ID Packets
```
  0                                       1
  0   1   2   3   4   5   6   7   8   9   0   1   2   3   4   5
+---+---+---+---+---+---+---+---+---+---+---+---+---+---+---+---+
|    Zeroes     |                Temporary ID                   |
+---+---+---+---+---+---+---+---+---+---+---+---+---+---+---+---+
```
- `Zeroes (4 bits)` - Used for the identification of ID packets.
- `Temporary ID (12 bits)` - Used for storing the TID of a node.

### 3.2 Routing Packets
```
  0                                           1
  0   1   2   3   4   5   6   7   8   9   0   1   2   3   4   5
+---+---+---+---+---+---+---+---+---+---+---+---+---+---+---+---+
|       | Number|       |  Next |       |  Next |       |  Next |
|  ID   |   of  |  Dst  |  Hop  |  Dst  |  Hop  |  Dst  |  Hop  |
|       | Routes|   1   |   1   |   2   |   2   |   3   |   3   |
+---+---+---+---+---+---+---+---+---+---+---+---+---+---+---+---+
```
- `ID (2 bits)` - The owner of this routing table.
- `Number of Routes (2 bits)` - How many routes this packet contains.
- `Dst n (2 bits)` - A destination in the routing table.
- `Next Hop n (2 bits)` - The next hop towards the destination.

### 3.3 Acknowledgement Packets
```
  0                                       1
  0   1   2   3   4   5   6   7   8   9   0   1   2   3   4   5
+---+---+---+---+---+---+---+---+---+---+---+---+---+---+---+---+
|      Ones     |  Src  |  Dst  |Nxt Hop|    Sequence Number    |
+---+---+---+---+---+---+---+---+---+---+---+---+---+---+---+---+
```

- `Ones (4 bits)` - Used for the identification of ACK packets.
- `Src (2 bits)` - The sender of this acknowledgement packet.
- `Dst (2 bits)` - The destination of this acknowledgement packet.
- `Next Hop (2 bits)` - The next hop towards the destination.
- `Sequence Number (6 bits)` - The sequence number of the message to be acknowledged.

### 3.4 Data packets
```
  0                                       1
  0   1   2   3   4   5   6   7   8   9   0   1   2   3   4   5
+---+---+---+---+---+---+---+---+---+---+---+---+---+---+---+---+
|  Src  |  Dst  |     Flags     |Nxt Hop|    Sequence Number    |
+---+---+---+---+---+---+---+---+---+---+---+---+---+---+---+---+
/                                                               /
\                    30 bytes of actual Data                    \
/                                                               /
+---+---+---+---+---+---+---+---+---+---+---+---+---+---+---+---+
```

- `Src (2 bits)` - The sender of this data packet.
- `Dst (2 bits)` - The destination of this data packet.
- `Flags / Control Bits (4 bits)`:
    - `SOM (1st bit)` - “Start of Message”, beginning of a fragmented message.
    - `EOM (2nd bit)` - “End of Message”, end of a fragmented message.
    - `BRD (3rd bit)` - “Broadcast”, indicate if the packet is a broadcast.
    - `EMPTY (4th bit)` - An unused control flag.
- `Nxt Hop (2 bits)` - The next hop towards the destination
- `Sequence Number (6 bits)` - The sequence number of this packet.
- `Data (30 bytes)` - The actual data to be transmitted.

## 4. Testing
All of the enumerated tests can be performed with any topology (webpage presets or custom). The system assumes that the maximum size of a network is 4 nodes.

### 4.1 Medium Access Control Testing
To test the medium access control functionality, we will instantiate 4 nodes in the system and observe their behavior during the discovery phase and after. We will verify if the nodes send their IDs at random intervals during the discovery phase and if they use their IDs to determine their turn for sending data after exiting the discovery phase. To confirm this, we can analyze in parallel the data from the console and from the emulator webpage.

### 4.2 Reliable Broadcasting Testing
To test the reliable broadcasting functionality, we will simulate a node broadcasting a message and verify if it creates acknowledgment timeouts for each receiving node. We will also check if the node resends the broadcast to nodes that have not acknowledged the message within the `ACKNOWLEDGEMENT_TTL`. To check this, uncomment lines 179, 191, and 194 in the `MyProtocol` class and observe the console on both nodes. The same testing can be done to ensure reliable data transfer of a whisper. The testing can also be performed with the introduction of “packet loss” through the webpage.

### 4.3 Multi-Hop Forwarding Testing
To test the multi-hop forwarding functionality, we send a message from a node, be that a broadcast or a whisper and verify if it forwards the packet to all intended receivers. This can be done by analyzing in parallel the consoles and the emulator webpage. During this test, we can also ensure that there are no collisions after exiting the “discovery phase” and sending `DATA` packets. This can be observed by checking if all nodes receive a message and by looking at the webpage.

### 4.4 Dynamic Addressing Testing
To test the dynamic addressing functionality, we will simulate nodes entering the network during the discovery phase and verify if they broadcast their TIDs at random intervals (uncomment line 761 of the `MyProtocol` class). We will also check if the nodes respond to TID packets by broadcasting their own TID and the list of already-known TIDs. We will simulate situations where the discovery phase ends due to reaching the maximum number of nodes or exceeding the time limit, and verify if the nodes use the index of their own TID in the sorted list as their permanent identifier. We will also check if the nodes periodically broadcast their own TID. Look in the console for messages notifying you about the received TIDs and about the assignment of a permanent ID.

### 4.5 Fragmentation Testing
To test the fragmentation functionality, we will send messages that are too long to fit in a single data packet (send a message consisting of more than 30 characters) and verify if they are correctly fragmented into packets with 30 bytes of data each. We will also verify that the receivers correctly combine the fragments into the original message and display it in the TUI. This can be done by observing the console of the sending and receiving nodes.

### 4.6 Routing Testing
To test the routing functionality, we will simulate nodes leaving the transmission range of other nodes, and reentering them. After performing any topology change (by using the webpage presets or dragging the nodes), the nodes should be able to update their routing tables (max 15 seconds) so that they correctly identify the reachable nodes. To view information about a node’s routing table, uncomment the lines 890, 899, and 902 in the `MyProtocol` class. You can also use the `/online` command to see the list of currently reachable nodes.
