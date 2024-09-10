public class Route {
	/** The node to contact for this route */
    public int nextHop;
    
    /** 1 for indirect routes, 0 for direct routes*/
    public int cost;
    
    /** Time to live in ms */
    public int ttl;

    public Route(int nextHop, int cost, int ttl) {
        this.nextHop = nextHop;
        this.cost = cost;
        this.ttl = ttl;
    }
}