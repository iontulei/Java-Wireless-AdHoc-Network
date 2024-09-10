import java.nio.ByteBuffer;

import client.Message;
import client.MessageType;

/**
 * A data packet consists of
 * src - 2 bits
 * dst - 2 bits
 * flg - 4 bits
 * nxt - 2 bits
 * seq - 6 bits
 * @param m
 * @throws InterruptedException
 */
public class Packet {
	int src;
	int dst;
	int flg;
	int nxt;
	int seq;
	String dat;

	public Packet(ByteBuffer message) {
		src = (message.get(0) >> 6) & 0b11;
		dst = (message.get(0) >> 4) & 0b11;
		flg = message.get(0) & 0b1111;
		nxt = (message.get(1) >> 6) & 0b11;
		seq = message.get(1) & 0b111111;
		
		// Data
        StringBuilder sb = new StringBuilder();
        for(int i = 2; i < 32; i++) {
            sb.append((char) message.get(i));
        }
		// added sb.length() > 0 &&
		// !!! this must be before accessing the sb.charAt because it will throw an index out of bounds
		while (sb.length() > 0 && (byte) sb.charAt(sb.length() - 1) == 0) {
			sb.deleteCharAt(sb.length() - 1);
		}
        dat = sb.toString();
	}
	
	public Packet(int src, int dst, int flags, int next, int seq, ByteBuffer data) {
		this.src = src;
		this.dst = dst;
		this.flg = flags;
		this.nxt = next;
		this.seq = seq;
//		this.dat = data.toString();

		// changed i < 30 to i < data.limit()
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < data.limit(); i++) {
			sb.append((char) data.get(i));
		}
		// added sb.length() > 0 &&
		// !!! this must be before accessing the sb.charAt because it will throw an index out of bounds
		while (sb.length() > 0 && (byte) sb.charAt(sb.length() - 1) == 0) {
			sb.deleteCharAt(sb.length() - 1);
		}

		this.dat = sb.toString();
	}
	
	public Message toMessage() {
        ByteBuffer message = ByteBuffer.allocate(32);
        byte firstByte = (byte) (((src & 0b11) << 6) | ((dst & 0b11) << 4) | (flg & 0b1111));
        message.put(firstByte);
        byte secondByte = (byte) (((nxt & 0b11) << 6) | (seq & 0b111111));
        message.put(secondByte);

		// changed i < 30 to i < dat.length()
        for(int i = 0; i < dat.length(); i++) {
            message.put(dat.getBytes()[i]);
        }

        return new Message(MessageType.DATA, message);
	}
	
	/**
	 * Allows for
	 * System.out.println(pkt);
	 */
	public String toString() {
		String str = "";
        str += "Message: ";
        str += "SRC: " + src;
        str += ", DEST: " + dst;
        str += ", FLAGS: " + flg;
        str += ", NEXT: " + nxt;
        str += ", SEQ: " + seq;
        str += ", Content: " + dat;
        str += "\n";
        return str;
	}
}
