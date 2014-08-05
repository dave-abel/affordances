package minecraft;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import minecraft.MapIO;
import minecraft.MinecraftStateParser;
import minecraft.NameSpace;
import minecraft.MinecraftDomain.MinecraftDomainGenerator;
import minecraft.MinecraftDomain.MacroActions.MinecraftMacroActionWrapper;
import minecraft.MinecraftDomain.MacroActions.SprintMacroActionWrapper;
import minecraft.MinecraftDomain.Options.TrenchBuildOption;
import affordances.KnowledgeBase;
import burlap.behavior.affordances.AffordancesController;
import burlap.behavior.singleagent.*;
import burlap.behavior.singleagent.planning.OOMDPPlanner;
import burlap.behavior.singleagent.planning.QComputablePlanner;
import burlap.behavior.singleagent.planning.commonpolicies.AffordanceGreedyQPolicy;
import burlap.behavior.singleagent.planning.commonpolicies.GreedyDeterministicQPolicy;
import burlap.behavior.singleagent.planning.commonpolicies.GreedyQPolicy;
import burlap.behavior.singleagent.planning.deterministic.DeterministicPlanner;
import burlap.behavior.singleagent.planning.deterministic.SDPlannerPolicy;
import burlap.behavior.singleagent.planning.deterministic.TFGoalCondition;
import burlap.behavior.singleagent.planning.deterministic.uninformed.bfs.BFS;
import burlap.behavior.singleagent.planning.stochastic.rtdp.AffordanceRTDP;
import burlap.behavior.singleagent.planning.stochastic.rtdp.RTDP;
import burlap.behavior.singleagent.planning.stochastic.valueiteration.AffordanceValueIteration;
import burlap.behavior.singleagent.planning.stochastic.valueiteration.ValueIteration;
import burlap.oomdp.auxiliary.StateParser;
import burlap.oomdp.core.*;
import burlap.oomdp.logicalexpressions.LogicalExpression;
import burlap.oomdp.logicalexpressions.PFAtom;
import burlap.oomdp.singleagent.*;
import burlap.oomdp.singleagent.common.SingleGoalLERF;
import burlap.oomdp.singleagent.common.SingleGoalMultipleLERF;
import burlap.oomdp.singleagent.common.SingleLETF;
import burlap.behavior.statehashing.DiscreteStateHashFactory;
import burlap.behavior.statehashing.StateHashFactory;
import minecraft.MinecraftStateGenerator.MinecraftStateGenerator;
import minecraft.MinecraftStateGenerator.Exceptions.StateCreationException;
import subgoals.*;

/**
 * The main behavior class for the minecraft domain
 * @author Dhershkowitz
 *
 */
public class MinecraftBehavior {
    // ----- CLASS variable -----
	private MinecraftDomainGenerator	MCDomainGenerator;
	private Domain						domain;
	private StateParser					MCStateParser;
	private RewardFunction				rewardFunction;
	private TerminalFunction			terminalFunction;
	private State						initialState;
	private DiscreteStateHashFactory	hashingFactory;
	private LogicalExpression 			currentGoal;
	
	//Propositional Functions
	public PropositionalFunction		pfAgentAtGoal;
	public PropositionalFunction		pfEmptySpace;
	public PropositionalFunction		pfBlockAt;
	public PropositionalFunction		pfAgentHasAtLeastXGoldOre;
	public PropositionalFunction		pfAgentHasAtLeastXGoldBar;
	public PropositionalFunction		pfBlockInFrontOfAgent;
	public PropositionalFunction		pfEndOfMapInFrontOfAgent;
	public PropositionalFunction		pfTrenchInFrontOfAgent;
	public PropositionalFunction		pfAgentInMidAir;
	public PropositionalFunction		pfTower;
	public PropositionalFunction		pfAgentInLava;
	
	// Dave's jenky hard coded prop funcs
	public PropositionalFunction		pfAgentLookForwardAndWalkable;
	public PropositionalFunction		pfTrenchBetweenAgentAndGoal;
	public PropositionalFunction		pfEmptyCellFrontAgentWalk;
	public PropositionalFunction		pfGoldBlockFrontOfAgent;
	public PropositionalFunction		pfFurnaceInFrontOfAgent;
	public PropositionalFunction		pfWallInFrontOfAgent;
	public PropositionalFunction		pfFeetBlockedHeadClear;
	public PropositionalFunction 		pfLavaFrontAgent;
	
	//Params for Planners
	private double						gamma = 0.99;
	private double						minDelta = .01;
	private int							maxSteps = 200;
	private int 						numRollouts = 2500; // RTDP
	private int							maxDepth = 70; // RTDP
	private int 						vInit = 1; // RTDP
	private int 						numRolloutsWithSmallChangeToConverge = 10; // RTDP
	private double						boltzmannTemperature = 0.5;
	private double						lavaReward = -10.0;

	// ----- CLASS METHODS -----
	/**
	 * Constructor to instantiate behavior
	 * @param filePath map filepath on which to perform the planning
	 */
	public MinecraftBehavior(String filePath) {
		MapIO mapIO = new MapIO(filePath);
		this.updateMap(mapIO);	
	}
	
	
	public MinecraftBehavior(MapIO mapIO) {
		this.updateMap(mapIO);	
	}
	
	/**
	 * 
	 * @param filePathOfMap a filepath to the location of the ascii map to update the behavior to
	 */
	public void updateMap(MapIO mapIO) {
		//Perform IO on map�
		
		char[][][] mapAs3DArray = mapIO.getMapAs3DCharArray();
		HashMap<String, Integer> headerInfo = mapIO.getHeaderHashMap();
		
		//Update domain
		this.MCDomainGenerator = new MinecraftDomainGenerator(mapAs3DArray, headerInfo);
		this.domain = MCDomainGenerator.generateDomain();
		
		//Set state parser
		this.MCStateParser = new MinecraftStateParser(domain);
		
		// Set up the state hashing system
		this.hashingFactory = new DiscreteStateHashFactory();
		this.hashingFactory.setAttributesForClass(NameSpace.CLASSAGENT, domain.getObjectClass(NameSpace.CLASSAGENT).attributeList); 
		this.hashingFactory.setAttributesForClass(NameSpace.CLASSDIRTBLOCKNOTPICKUPABLE, domain.getObjectClass(NameSpace.CLASSDIRTBLOCKNOTPICKUPABLE).attributeList);
		this.hashingFactory.setAttributesForClass(NameSpace.CLASSDIRTBLOCKPICKUPABLE, domain.getObjectClass(NameSpace.CLASSDIRTBLOCKPICKUPABLE).attributeList);
		this.hashingFactory.setAttributesForClass(NameSpace.CLASSGOLDBLOCK, domain.getObjectClass(NameSpace.CLASSGOLDBLOCK).attributeList);

		//Set initial state
		try {
			this.initialState = MinecraftStateGenerator.createInitialState(mapAs3DArray, headerInfo, domain);
		} catch (StateCreationException e) {
			e.printStackTrace();
		}
		
		// Get propositional functions
		this.pfAgentAtGoal = domain.getPropFunction(NameSpace.PFATGOAL);
		this.pfEmptySpace = domain.getPropFunction(NameSpace.PFEMPSPACE);
		this.pfBlockAt = domain.getPropFunction(NameSpace.PFBLOCKAT);
		this.pfAgentHasAtLeastXGoldOre = domain.getPropFunction(NameSpace.PFATLEASTXGOLDORE);
		this.pfAgentHasAtLeastXGoldBar = domain.getPropFunction(NameSpace.PFATLEASTXGOLDBAR);
		this.pfBlockInFrontOfAgent = domain.getPropFunction(NameSpace.PFBLOCKINFRONT);
		this.pfEndOfMapInFrontOfAgent = domain.getPropFunction(NameSpace.PFENDOFMAPINFRONT);
		this.pfTrenchInFrontOfAgent = domain.getPropFunction(NameSpace.PFEMPTYCELLINFRONT);
		this.pfAgentInMidAir = domain.getPropFunction(NameSpace.PFAGENTINMIDAIR);
		this.pfAgentLookForwardAndWalkable = domain.getPropFunction(NameSpace.PFAGENTLOOKFORWARDWALK);
		this.pfEmptyCellFrontAgentWalk = domain.getPropFunction(NameSpace.PFEMPTYCELLINWALK);
		this.pfTower = domain.getPropFunction(NameSpace.PFTOWER);
		this.pfGoldBlockFrontOfAgent = domain.getPropFunction(NameSpace.PFGOLDFRONTAGENTONE);
		this.pfFurnaceInFrontOfAgent = domain.getPropFunction(NameSpace.PFFURNACEINFRONT);
		this.pfWallInFrontOfAgent = domain.getPropFunction(NameSpace.PFWALLINFRONT);
		this.pfFeetBlockedHeadClear = domain.getPropFunction(NameSpace.PFFEETBLOCKHEADCLEAR);
		this.pfAgentInLava = domain.getPropFunction(NameSpace.PFAGENTINLAVA);
		this.pfLavaFrontAgent = domain.getPropFunction(NameSpace.PFLAVAFRONTAGENT);
		
		// Set up goal LE and lava LE for use in reward function
		PropositionalFunction pfToUse = getPFFromHeader(headerInfo);
		this.currentGoal = new PFAtom(pfToUse.getAllGroundedPropsForState(this.initialState).get(0)); 
		LogicalExpression lavaLE = new PFAtom(this.pfAgentInLava.getAllGroundedPropsForState(this.initialState).get(0));
		
		// Set up reward function
		HashMap<LogicalExpression, Double> rewardMap = new HashMap<LogicalExpression, Double>();
		rewardMap.put(this.currentGoal, 0.0);
		rewardMap.put(lavaLE, this.lavaReward);
		this.rewardFunction = new SingleGoalMultipleLERF(rewardMap, -1);
		
//		this.rewardFunction = new SingleGoalLERF(currentGoal, 0, -1); 
		
		//Set up terminal function
		this.terminalFunction = new SingleLETF(currentGoal);
				
//		//Set up reward function
//		this.rewardFunction = new SingleGoalPFRF(pfToUse, 0, -1); 
//		
//		//Set up terminal function
//		this.terminalFunction = new SinglePFTF(pfToUse);

	}
	
	private PropositionalFunction getPFFromHeader(HashMap<String, Integer> headerInfo) {
		switch(headerInfo.get(Character.toString(NameSpace.CHARGOALDESCRIPTOR))) {
		case NameSpace.INTXYZGOAL:
			return this.pfAgentAtGoal;
		
		case NameSpace.INTGOLDOREGOAL:
			return this.pfAgentHasAtLeastXGoldOre;
			
		case NameSpace.INTGOLDBARGOAL:
			return this.pfAgentHasAtLeastXGoldBar;
		case NameSpace.INTTOWERGOAL:
			return this.pfTower;
		default:
			break;
		}
		
		return null;

	}
	
	// --- ACCESSORS ---
	
	public Domain getDomain() {
		return this.domain;
	}
	
	public RewardFunction getRewardFunction() {
		return this.rewardFunction;
	}
	
	public TerminalFunction getTerminalFunction() {
		return this.terminalFunction;
	}
	
	public double getGamma() {
		return this.gamma;
	}
	
	public DiscreteStateHashFactory getHashFactory() {
		return this.hashingFactory;
	}

	public double getMinDelta() {
		return this.minDelta;
	}
	
	public State getInitialState() {
		return this.initialState;

	}
	
	public MinecraftDomainGenerator getDomainGenerator() {
		return this.MCDomainGenerator;
	}
	
	private void addOptionsToOOMDPPlanner(OOMDPPlanner toAddTo, boolean addOptions, boolean addMAs) {
		//OPTIONS
		if (addOptions) {
			//Trench build option
			toAddTo.addNonDomainReferencedAction(new TrenchBuildOption(NameSpace.OPTBUILDTRENCH, this.initialState, this.domain,
					this.rewardFunction, this.gamma, this.hashingFactory));
		}

		//MACROACTIONS
		if (addMAs) {
			//Sprint macro-action
			MinecraftMacroActionWrapper sprintWrapper = new SprintMacroActionWrapper(NameSpace.MACROACTIONSPRINT,this.initialState, this.domain, this.hashingFactory, this.rewardFunction, this.gamma, 2);
			toAddTo.addNonDomainReferencedAction(sprintWrapper.getMacroAction());	
		}	
	}
	
	// ---------- PLANNERS ---------- 
	
	/**
	 * Takes in an instance of an OOMDP planner and solves the OO-MDP
	 * @param planner
	 * @return p: The Policy from the solved OO-MDP
	 */
	public Policy solve(OOMDPPlanner planner) {
		// Solve the OO-MDP
		planner.planFromState(initialState);

		// Create a Q-greedy policy from the planner
		GreedyDeterministicQPolicy p = new GreedyDeterministicQPolicy((QComputablePlanner)planner);
		
		// Print out some infos
		EpisodeAnalysis ea = p.evaluateBehavior(initialState, this.rewardFunction, this.terminalFunction, maxSteps);
		
		System.out.println(ea.getActionSequenceString());

		return p;
	}
	
	public void BFSExample(boolean addOptions, boolean addMAs) {
		TFGoalCondition goalCondition = new TFGoalCondition(this.terminalFunction);
		
		DeterministicPlanner planner = new BFS(this.domain, goalCondition, this.hashingFactory);
		
		addOptionsToOOMDPPlanner(planner, addOptions, addMAs);
		
		planner.planFromState(initialState);
		
		Policy p = new SDPlannerPolicy(planner);
		
		p.evaluateBehavior(initialState, this.rewardFunction, this.terminalFunction);
		
		EpisodeAnalysis ea = p.evaluateBehavior(initialState, this.rewardFunction, this.terminalFunction);
		System.out.println(ea.getActionSequenceString());
	}
	
	private double sumReward(List<Double> rewardSeq) {
		double total = 0;
		for (double d : rewardSeq) {
			total += d;
		}
		return total;
	}
	
	public double[] ValueIterationPlanner(boolean addOptions, boolean addMAs){
		TFGoalCondition goalCondition = new TFGoalCondition(this.terminalFunction);

		ValueIteration planner = new ValueIteration(domain, rewardFunction, terminalFunction, gamma, hashingFactory, 0.01, Integer.MAX_VALUE);
		addOptionsToOOMDPPlanner(planner, addOptions, addMAs);

		long startTime = System.currentTimeMillis( );
		
		int bellmanUpdates = planner.planFromStateAndCount(initialState);
		
		// Create a Q-greedy policy from the planner
		Policy p = new GreedyQPolicy((QComputablePlanner)planner);
		
		// Record the plan results to a file
		EpisodeAnalysis ea = p.evaluateBehavior(initialState, rewardFunction, terminalFunction, maxSteps);

		long totalPlanningTime  = System.currentTimeMillis( ) - startTime;
		System.out.println(ea.getActionSequenceString());
		// Count reward.
		double totalReward = 0.;
		for(Double d : ea.rewardSequence){
			totalReward = totalReward + d;
		}
		
		State finalState = ea.stateSequence.get(ea.stateSequence.size()-1);
		double completed = goalCondition.satisfies(finalState) ? 1.0 : 0.0;
		
		double[] results = {bellmanUpdates, totalReward, completed, totalPlanningTime};
		return results;
	}
	
	public double[] AffordanceVI(KnowledgeBase affKB, boolean addOptions, boolean addMAs){
		AffordancesController affController = affKB.getAffordancesController();
		affController.setCurrentGoal(this.currentGoal); // Update goal to determine active affordances
		
		// Setup goal condition and planner
		TFGoalCondition goalCondition = new TFGoalCondition(this.terminalFunction);
		AffordanceValueIteration planner = new AffordanceValueIteration(domain, rewardFunction, terminalFunction, gamma, hashingFactory, 0.01, Integer.MAX_VALUE, affController);
		addOptionsToOOMDPPlanner(planner, addOptions, addMAs);
		
		// Time
		long startTime = System.currentTimeMillis( );
		
		// Plan and record bellmanUpdates
		int bellmanUpdates = planner.planFromStateAndCount(initialState);
		
		// Create a Q-greedy policy from the planner
		Policy p = new AffordanceGreedyQPolicy(affController, (QComputablePlanner)planner);
		
		// Record the plan results to a file
		EpisodeAnalysis ea = p.evaluateBehavior(initialState, rewardFunction, terminalFunction, maxSteps);
		
		long totalPlanningTime  = System.currentTimeMillis( ) - startTime;
		
		// Count reward.
		double totalReward = 0.;
		for(Double d : ea.rewardSequence){
			totalReward = totalReward + d;
		}
		
		// Check to see if the planner found the goal
		State finalState = ea.stateSequence.get(ea.stateSequence.size()-1);
		double completed = goalCondition.satisfies(finalState) ? 1.0 : 0.0;
		
		
		double[] results = {bellmanUpdates, totalReward, completed, totalPlanningTime};
		return results;
	}
	
	public double[] AffordanceRTDP(KnowledgeBase affKB, boolean addOptions, boolean addMAs){
		AffordancesController affController = affKB.getAffordancesController();
		affController.setCurrentGoal(this.currentGoal); // Update goal to determine active affordances
		
		AffordanceRTDP planner = new AffordanceRTDP(domain, rewardFunction, terminalFunction, gamma, hashingFactory, vInit, numRollouts, minDelta, maxDepth, affController, numRolloutsWithSmallChangeToConverge);
		addOptionsToOOMDPPlanner(planner, addOptions, addMAs);
		
		long startTime = System.currentTimeMillis( );
		
		int bellmanUpdates = planner.planFromStateAndCount(initialState);

		// Create a Policy from the planner
//		Policy p = new AffordanceBoltzmannQPolicy((QComputablePlanner)planner, boltzmannTemperature, affController);
		Policy p = new AffordanceGreedyQPolicy(affController, (QComputablePlanner)planner);
		EpisodeAnalysis ea = p.evaluateBehavior(initialState, rewardFunction, terminalFunction, maxSteps);
		
		// Compute CPU time
		long totalPlanningTime  = System.currentTimeMillis( ) - startTime;
		
		// Count reward.
		double totalReward = 0.;
		for(Double d : ea.rewardSequence){
			totalReward = totalReward + d;
		}
		
		// Check if task completed
		State finalState = ea.getState(ea.stateSequence.size() - 1);
		double completed = terminalFunction.isTerminal(finalState) ? 1.0 : 0.0;
		
//		System.out.println(ea.getActionSequenceString());

		double[] results = {bellmanUpdates, totalReward, completed, totalPlanningTime};
		
		return results;
	}
	
	/**
	 * Solves the current OO-MDP using Real Time Dynamic Programming
	 * @return: The number of bellman updates performed during planning
	 */
	public double[] RTDP(boolean addOptions, boolean addMAs) {

		RTDP planner = new RTDP(domain, rewardFunction, terminalFunction, gamma, hashingFactory, vInit, numRollouts, minDelta, maxDepth);
		
		addOptionsToOOMDPPlanner(planner, addOptions, addMAs);
		
		planner.setMinNumRolloutsWithSmallValueChange(numRolloutsWithSmallChangeToConverge);
		
		long startTime = System.currentTimeMillis( );
		
		int bellmanUpdates = planner.planFromStateAndCount(initialState);
		// Create a Q-greedy policy from the planner
		Policy p = new GreedyQPolicy((QComputablePlanner)planner);
		EpisodeAnalysis ea = p.evaluateBehavior(initialState, rewardFunction, terminalFunction, maxSteps);
		
		// Compute CPU time
		long totalPlanningTime  = System.currentTimeMillis( ) - startTime;
		
		// Count reward
		double totalReward = 0.;
		for(Double d : ea.rewardSequence){
			totalReward = totalReward + d;
		}
		
		// Check if task completed
		State finalState = ea.getState(ea.stateSequence.size() - 1);
		double completed = terminalFunction.isTerminal(finalState) ? 1.0 : 0.0;
		
		System.out.println(ea.getActionSequenceString());

		double[] results = {bellmanUpdates, totalReward, completed, totalPlanningTime};

		return results;
	}
	
	public double[] SubgoalPlanner(OOMDPPlanner planner) {
		
		List<Subgoal> subgoalKB = new ArrayList<Subgoal>();
		
		LogicalExpression hasOreLE = new PFAtom(this.pfAgentHasAtLeastXGoldOre.getAllGroundedPropsForState(this.initialState).get(0));
		LogicalExpression hasGoldLE = new PFAtom(this.pfAgentHasAtLeastXGoldBar.getAllGroundedPropsForState(this.initialState).get(0));
		Subgoal hasOreSG = new Subgoal(hasOreLE, hasGoldLE);
		subgoalKB.add(hasOreSG);
		
		SubgoalPlanner sgPlanner = new SubgoalPlanner(domain, initialState, rewardFunction, terminalFunction, planner, subgoalKB);
		sgPlanner.solve();
		
		return null;
	}
	
	public static void main(String[] args) {
		String mapsPath = "src/minecraft/maps/";
		String outputPath = "src/minecraft/planningOutput/";
		
		String mapName = "PlaneWorld0.map";
		
		MinecraftBehavior mcBeh = new MinecraftBehavior(mapsPath + mapName);

		// BFS
//		mcBeh.BFSExample(true, true);


		// VI
//		double[] results = mcBeh.ValueIterationPlanner();

//		System.out.println("(minecraftBehavior) results: " + results[0] + "," + results[1] + "," + results[2] + "," + results[3]);
		
		// Affordance VI
//		KnowledgeBase affKB = new KnowledgeBase();
//		affKB.loadHard(mcBeh.getDomain(), "expert.kb");
//		results = mcBeh.AffordanceVI(affKB);
//		System.out.println("(minecraftBehavior) results: " + results[0] + "," + results[1] + "," + results[2] + "," + results[3]);
		
		// Affordance RTDP
//		KnowledgeBase affKB = new KnowledgeBase();
//		affKB.loadHard(mcBeh.getDomain(), "test50.kb");
//		double[] results = mcBeh.AffordanceRTDP(affKB, false, false);
//		System.out.println("(minecraftBehavior) results: [affRTDP] " + results[0] + "," + results[1] + "," + results[2] + "," + String.format("%.2f", results[3] / 1000) + "s");
		
		// Subgoal Planner
//		OOMDPPlanner lowLevelPlanner = new RTDP(mcBeh.domain, mcBeh.rewardFunction, mcBeh.terminalFunction, mcBeh.gamma, mcBeh.hashingFactory, mcBeh.vInit, mcBeh.numRollouts, mcBeh.minDelta, mcBeh.maxDepth);
//		mcBeh.SubgoalPlanner(lowLevelPlanner);
		
		//		SubgoalKnowledgeBase subgoalKB = new SubgoalKnowledgeBase(mapName, mcBeh.domain);
//		List<Subgoal> highLevelPlan = subgoalKB.generateSubgoalKB(mapName);
//		SubgoalPlanner sgp = new SubgoalPlanner(mcBeh.domain, mcBeh.getInitialState(), mcBeh.rewardFunction, mcBeh.terminalFunction, lowLevelPlanner, highLevelPlan);
//		sgp.solve();
		
		// RTDP
		double[] results = mcBeh.RTDP(true, true);
		System.out.println("(minecraftBehavior) results: " + results[0] + "," + results[1] + "," + results[2] + "," + results[3]);
		
		// Collect results and write to file
//		File resultsFile = new File("src/tests/results/mcBeh_results.txt");
//		BufferedWriter bw;
//		FileWriter fw;
//		try {
//			fw = new FileWriter(resultsFile.getAbsoluteFile());
//			bw = new BufferedWriter(fw);
//			bw.write("(minecraftBehavior) results: VI " + results[0] + "," + results[1] + "," + results[2] + "," + String.format("%.2f", results[3] / 1000) + "s");
//			bw.flush();
//		} catch (IOException e) {
//			// TODO Auto-generated catch block
//			e.printStackTrace();
//		}
		
	}
	
	
}