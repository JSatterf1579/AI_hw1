package edu.cwru.sepia.agent;

import edu.cwru.sepia.action.Action;
import edu.cwru.sepia.environment.model.history.History;
import edu.cwru.sepia.environment.model.state.ResourceNode;
import edu.cwru.sepia.environment.model.state.State;
import edu.cwru.sepia.environment.model.state.Unit;
import edu.cwru.sepia.util.Direction;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.*;

public class AstarAgent extends Agent {


    class MapLocation implements Comparable<MapLocation>
    {
        public int x, y;

        public MapLocation cameFrom; // previous location on map from search

        public float cost; // Cost of reaching this location on the map from the start node

        public float heuristic; // the chebyshev distance from this node to the goal


        /**
        * Constructor used to initialize a MapLocation in search.
        * @param x the column that the cell is in
        * @param y the row the cell is in
        * @param cameFrom the previous cell in the A* search
        * @param cost the cost to reach this node in A* search
        * @param heuristic the chebyshev distance from this cell to the goal
        */
        public MapLocation(int x, int y, MapLocation cameFrom, float cost, float heuristic)
        {
            this.x = x;
            this.y = y;
            this.cameFrom = cameFrom;
            this.cost = cost;
            this.heuristic = heuristic;
        }

        /**
        * Constructor used to initialize a MapLocation when all info is not known.
        * @param x the column that the cell is in
        * @param y the row the cell is in
        */
        public MapLocation(int x, int y) {
            this.x = x;
            this.y = y;
            this.cost = Integer.MAX_VALUE;
            this.heuristic = Integer.MAX_VALUE;
            this.cameFrom = null;
        }

        /**
        * Returns true if the given object is a MapLocation with the same coordinates.
        * @param other The other cell in question
        * @return
        */
        public boolean equals(Object other) {

            if (other != null && other instanceof  MapLocation) {
                MapLocation oMap = (MapLocation)other;
                if (this.x == oMap.x && this.y == oMap.y) {
                    return true;
                } else {
                    return false;
                }
            }
            return  false;
        }

        /**
        * Used in Priority queues to order MapLocations.  A MapLocation is "less" when 
        * it has a lower total cost than another MapLocation
        * @param other
        * @return -1 if less, 0 if same, 1 if more
        */
        public int compareTo(MapLocation other) {
            return Float.compare(this.heuristic + this.cost, other.heuristic + other.cost);
        }

        /**
        * Used for hashcoding
        * @return the string version of the coordinates of the cell
        */
        public String toString() {
            return "(" + x + "," + y + ")";
        }

        /**
        * Implemented to allow for ArrayList.contains to work
        * @return the hash of the toString of this object. Equivalent cells will hash to the same value
        */
        public int hashCode() {
            return this.toString().hashCode();
        }
    }

    Stack<MapLocation> path;
    int footmanID, townhallID, enemyFootmanID;
    MapLocation nextLoc;

    private long totalPlanTime = 0; // nsecs
    private long totalExecutionTime = 0; //nsecs

    public AstarAgent(int playernum)
    {
        super(playernum);

        System.out.println("Constructed AstarAgent");
    }

    @Override
    public Map<Integer, Action> initialStep(State.StateView newstate, History.HistoryView statehistory) {
        // get the footman location
        List<Integer> unitIDs = newstate.getUnitIds(playernum);

        if(unitIDs.size() == 0)
        {
            System.err.println("No units found!");
            return null;
        }

        footmanID = unitIDs.get(0);

        // double check that this is a footman
        if(!newstate.getUnit(footmanID).getTemplateView().getName().equals("Footman"))
        {
            System.err.println("Footman unit not found");
            return null;
        }

        // find the enemy playernum
        Integer[] playerNums = newstate.getPlayerNumbers();
        int enemyPlayerNum = -1;
        for(Integer playerNum : playerNums)
        {
            if(playerNum != playernum) {
                enemyPlayerNum = playerNum;
                break;
            }
        }

        if(enemyPlayerNum == -1)
        {
            System.err.println("Failed to get enemy playernumber");
            return null;
        }

        // find the townhall ID
        List<Integer> enemyUnitIDs = newstate.getUnitIds(enemyPlayerNum);

        if(enemyUnitIDs.size() == 0)
        {
            System.err.println("Failed to find enemy units");
            return null;
        }

        townhallID = -1;
        enemyFootmanID = -1;
        for(Integer unitID : enemyUnitIDs)
        {
            Unit.UnitView tempUnit = newstate.getUnit(unitID);
            String unitType = tempUnit.getTemplateView().getName().toLowerCase();
            if(unitType.equals("townhall"))
            {
                townhallID = unitID;
            }
            else if(unitType.equals("footman"))
            {
                enemyFootmanID = unitID;
            }
            else
            {
                System.err.println("Unknown unit type");
            }
        }

        if(townhallID == -1) {
            System.err.println("Error: Couldn't find townhall");
            return null;
        }

        long startTime = System.nanoTime();
        path = findPath(newstate);
        totalPlanTime += System.nanoTime() - startTime;

        return middleStep(newstate, statehistory);
    }

    @Override
    public Map<Integer, Action> middleStep(State.StateView newstate, History.HistoryView statehistory) {
        long startTime = System.nanoTime();
        long planTime = 0;

        Map<Integer, Action> actions = new HashMap<Integer, Action>();

        if(shouldReplanPath(newstate, statehistory, path)) {
            long planStartTime = System.nanoTime();
            path = findPath(newstate);
            planTime = System.nanoTime() - planStartTime;
            totalPlanTime += planTime;
        }

        Unit.UnitView footmanUnit = newstate.getUnit(footmanID);

        int footmanX = footmanUnit.getXPosition();
        int footmanY = footmanUnit.getYPosition();

        if(!path.empty() && (nextLoc == null || (footmanX == nextLoc.x && footmanY == nextLoc.y))) {

            // stat moving to the next step in the path
            nextLoc = path.pop();

            System.out.println("Moving to (" + nextLoc.x + ", " + nextLoc.y + ")");
        }

        if(nextLoc != null && (footmanX != nextLoc.x || footmanY != nextLoc.y))
        {
            int xDiff = nextLoc.x - footmanX;
            int yDiff = nextLoc.y - footmanY;

            // figure out the direction the footman needs to move in
            Direction nextDirection = getNextDirection(xDiff, yDiff);

            actions.put(footmanID, Action.createPrimitiveMove(footmanID, nextDirection));
        } else {
            Unit.UnitView townhallUnit = newstate.getUnit(townhallID);

            // if townhall was destroyed on the last turn
            if(townhallUnit == null) {
                terminalStep(newstate, statehistory);
                return actions;
            }

            if(Math.abs(footmanX - townhallUnit.getXPosition()) > 1 ||
                    Math.abs(footmanY - townhallUnit.getYPosition()) > 1)
            {
                System.err.println("Invalid plan. Cannot attack townhall");
                totalExecutionTime += System.nanoTime() - startTime - planTime;
                return actions;
            }
            else {
                System.out.println("Attacking TownHall");
                // if no more movements in the planned path then attack
                actions.put(footmanID, Action.createPrimitiveAttack(footmanID, townhallID));
            }
        }

        totalExecutionTime += System.nanoTime() - startTime - planTime;
        return actions;
    }

    @Override
    public void terminalStep(State.StateView newstate, History.HistoryView statehistory) {
        System.out.println("Total turns: " + newstate.getTurnNumber());
        System.out.println("Total planning time: " + totalPlanTime/1e9);
        System.out.println("Total execution time: " + totalExecutionTime/1e9);
        System.out.println("Total time: " + (totalExecutionTime + totalPlanTime)/1e9);
    }

    @Override
    public void savePlayerData(OutputStream os) {

    }

    @Override
    public void loadPlayerData(InputStream is) {

    }

    /**
     * You will implement this method.
     *
     * This method should return true when the path needs to be replanned
     * and false otherwise. This will be necessary on the dynamic map where the
     * footman will move to block your unit.
     *
     * @param state
     * @param history
     * @param currentPath
     * @return
     */
    private boolean shouldReplanPath(State.StateView state, History.HistoryView history, Stack<MapLocation> currentPath)
    {
        MapLocation enemyFootmanLoc = null;
        if(enemyFootmanID != -1) { // if the enemy exists, we find his location
            Unit.UnitView enemyFootmanUnit = state.getUnit(enemyFootmanID);
            enemyFootmanLoc = new MapLocation(enemyFootmanUnit.getXPosition(), enemyFootmanUnit.getYPosition());
            if (currentPath.contains(enemyFootmanLoc)) { // we worry about the footman if he is on our path
                return true;
            }
            return false; // else we don't
        } else {
            return false; // if the footman doesn't exist, we don't care
        }
    }

    /**
     * This method is implemented for you. You should look at it to see examples of
     * how to find units and resources in Sepia.
     *
     * @param state
     * @return
     */
    private Stack<MapLocation> findPath(State.StateView state)
    {
        Unit.UnitView townhallUnit = state.getUnit(townhallID);
        Unit.UnitView footmanUnit = state.getUnit(footmanID);

        MapLocation startLoc = new MapLocation(footmanUnit.getXPosition(), footmanUnit.getYPosition());

        MapLocation goalLoc = new MapLocation(townhallUnit.getXPosition(), townhallUnit.getYPosition());

        MapLocation footmanLoc = null;
        if(enemyFootmanID != -1) {
            Unit.UnitView enemyFootmanUnit = state.getUnit(enemyFootmanID);
            footmanLoc = new MapLocation(enemyFootmanUnit.getXPosition(), enemyFootmanUnit.getYPosition());
        }

        // get resource location
        List<Integer> resourceIDs = state.getAllResourceIds();
        Set<MapLocation> resourceLocations = new HashSet<MapLocation>();
        for(Integer resourceID : resourceIDs)
        {
            ResourceNode.ResourceView resource = state.getResourceNode(resourceID);

            resourceLocations.add(new MapLocation(resource.getXPosition(), resource.getYPosition()));
        }

        return AstarSearch(startLoc, goalLoc, state.getXExtent(), state.getYExtent(), footmanLoc, resourceLocations);
    }
    /**
     * This is the method you will implement for the assignment. Your implementation
     * will use the A* algorithm to compute the optimum path from the start position to
     * a position adjacent to the goal position.
     *
     * You will return a Stack of positions with the top of the stack being the first space to move to
     * and the bottom of the stack being the last space to move to. If there is no path to the townhall
     * then return null from the method and the agent will print a message and do nothing.
     * The code to execute the plan is provided for you in the middleStep method.
     *
     * As an example consider the following simple map
     *
     * F - - - -
     * x x x - x
     * H - - - -
     *
     * F is the footman
     * H is the townhall
     * x's are occupied spaces
     *
     * xExtent would be 5 for this map with valid X coordinates in the range of [0, 4]
     * x=0 is the left most column and x=4 is the right most column
     *
     * yExtent would be 3 for this map with valid Y coordinates in the range of [0, 2]
     * y=0 is the top most row and y=2 is the bottom most row
     *
     * resourceLocations would be {(0,1), (1,1), (2,1), (4,1)}
     *
     * The path would be
     *
     * (1,0)
     * (2,0)
     * (3,1)
     * (2,2)
     * (1,2)
     *
     * Notice how the initial footman position and the townhall position are not included in the path stack
     *
     * @param start Starting position of the footman
     * @param goal MapLocation of the townhall
     * @param xExtent Width of the map
     * @param yExtent Height of the map
     * @param resourceLocations Set of positions occupied by resources
     * @return Stack of positions with top of stack being first move in plan
     */
    private Stack<MapLocation> AstarSearch(MapLocation start, MapLocation goal, int xExtent, int yExtent, MapLocation enemyFootmanLoc, Set<MapLocation> resourceLocations)
    {
        boolean done = false;
        MapLocation current = null; // current expanding node in search
        PriorityQueue<MapLocation> nextLocs = new PriorityQueue<MapLocation>(); // open list of nodes to check, ordered by least cost
        ArrayList<MapLocation> closedList = new ArrayList<MapLocation>(); // closed list of expanded nodes
        
        //initialize start location statistics
        start.cost = 0;
        start.heuristic = chebyshev(start, goal);
        // start is our first node
        nextLocs.add(start);

        while (nextLocs.peek() != null) { //keep going while we have nodes to expand
            if(done) { // if we're done we can backtrack from the current(goal) node and return the path
                current = current.cameFrom; // ignore the goal, since we just want to get next to it
                Stack<MapLocation> returnPath = new Stack<MapLocation>(); 
                while (current.cameFrom != null) { // add all nodes except the start node
                    returnPath.add(current);
                    current = current.cameFrom;
                }
                return returnPath;
            }

            // otherwise, we want to expand the lowest cost element in the queue
            current = nextLocs.poll();
            closedList.add(current); // we won't be expanding this node anymore after this iteration
            List<MapLocation> neighbors = getValidNeighbors(current, xExtent, yExtent, enemyFootmanLoc, resourceLocations);
            for (MapLocation neighbor : neighbors) {
                //assign costs to the valid neighbor
                neighbor.heuristic = chebyshev(neighbor, goal);
                neighbor.cost = current.cost + 1;

                // ignore neighbors we've expanded before
                if(closedList.contains(neighbor)) {
                    continue;
                }
                if(!nextLocs.contains(neighbor)) { // add neighbor if it's not in the open list
                    nextLocs.add(neighbor);
                } else if (current.cost + 1 >= neighbor.cost) { // ignore neighbor that is in the open list and we haven't found a shorter path to
                    continue;
                }
                // set the previous pointer
                neighbor.cameFrom = current;


            }
            if (current.equals(goal)) { // check for end condition
                done = true;
            }

        }
        // This cna only be reached if we've found no path, so we just exit.
        System.out.println("No available path");
        System.exit(0);
        return new Stack<>();

    }

    /**
    * Calculates the chebyshev distance between two points
    * @param a node 1
    * @param b node 2
    * @return
    */
    private float chebyshev(MapLocation a, MapLocation b) {
        return Math.max(Math.abs(a.x - b.x), Math.abs(a.y - b.y));
    }

    /**
    * Returns all MapLocations adjacent to the given location that are on the map and are not under a resource or unit.
    * @param current the current node
    * @param xExtent the width of the map
    * @param yExtent the height of the map
    * @param enemyFootmanLoc the location of the enemy footman
    * @param resourceLocations location of all resources on map
    * @return a list of MapLocations of empty neighbor nodes
    */
    private List<MapLocation> getValidNeighbors(MapLocation current, int xExtent, int yExtent, MapLocation enemyFootmanLoc, Set<MapLocation> resourceLocations) {
        List<MapLocation> neighborList = new ArrayList<>();

        for (int x = -1; x < 2; x++) {
            for (int y = -1; y < 2; y++) { // iterate over all nodes around current
                if (current.x + x >= 0 && current.x + x < xExtent && current.y + y >= 0 && current.y + y < yExtent &&(x != 0 || y != 0)) { // check if it's on the map
                    MapLocation test = new MapLocation(current.x + x, current.y + y);
                    if (!resourceLocations.contains(test) && !test.equals(enemyFootmanLoc)) { // check if the location is occupied
                        neighborList.add(test);
                    }
                }
            }
        }

        return neighborList;
    }

    /**
     * Primitive actions take a direction (e.g. NORTH, NORTHEAST, etc)
     * This converts the difference between the current position and the
     * desired position to a direction.
     *
     * @param xDiff Integer equal to 1, 0 or -1
     * @param yDiff Integer equal to 1, 0 or -1
     * @return A Direction instance (e.g. SOUTHWEST) or null in the case of error
     */
    private Direction getNextDirection(int xDiff, int yDiff) {

        // figure out the direction the footman needs to move in
        if(xDiff == 1 && yDiff == 1)
        {
            return Direction.SOUTHEAST;
        }
        else if(xDiff == 1 && yDiff == 0)
        {
            return Direction.EAST;
        }
        else if(xDiff == 1 && yDiff == -1)
        {
            return Direction.NORTHEAST;
        }
        else if(xDiff == 0 && yDiff == 1)
        {
            return Direction.SOUTH;
        }
        else if(xDiff == 0 && yDiff == -1)
        {
            return Direction.NORTH;
        }
        else if(xDiff == -1 && yDiff == 1)
        {
            return Direction.SOUTHWEST;
        }
        else if(xDiff == -1 && yDiff == 0)
        {
            return Direction.WEST;
        }
        else if(xDiff == -1 && yDiff == -1)
        {
            return Direction.NORTHWEST;
        }

        System.err.println("Invalid path. Could not determine direction");
        return null;
    }
}
