// =================================================================                                                                   
// Copyright (C) 2011-2013 Pierre Lison (plison@ifi.uio.no)                                                                            
//                                                                                                                                     
// This library is free software; you can redistribute it and/or                                                                       
// modify it under the terms of the GNU Lesser General Public License                                                                  
// as published by the Free Software Foundation; either version 2.1 of                                                                 
// the License, or (at your option) any later version.                                                                                 
//                                                                                                                                     
// This library is distributed in the hope that it will be useful, but                                                                 
// WITHOUT ANY WARRANTY; without even the implied warranty of                                                                          
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU                                                                    
// Lesser General Public License for more details.                                                                                     
//                                                                                                                                     
// You should have received a copy of the GNU Lesser General Public                                                                    
// License along with this program; if not, write to the Free Software                                                                 
// Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA                                                                           
// 02111-1307, USA.                                                                                                                    
// =================================================================                                                                   

package opendial.simulation;


import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import opendial.arch.DialException;
import opendial.arch.Logger;
import opendial.arch.Settings;
import opendial.arch.StateListener;
import opendial.arch.Logger.Level;
import opendial.bn.Assignment;
import opendial.bn.distribs.ProbDistribution;
import opendial.bn.distribs.discrete.DiscreteProbDistribution;
import opendial.bn.distribs.discrete.SimpleTable;
import opendial.bn.distribs.empirical.DepEmpiricalDistribution;
import opendial.bn.distribs.utility.UtilityTable;
import opendial.bn.nodes.BNode;
import opendial.bn.nodes.ChanceNode;
import opendial.bn.nodes.ProbabilityRuleNode;
import opendial.bn.values.Value;
import opendial.bn.values.ValueFactory;
import opendial.domains.Domain;
import opendial.domains.Model;
import opendial.domains.rules.DecisionRule;
import opendial.gui.GUIFrame;
import opendial.inference.InferenceAlgorithm;
import opendial.inference.queries.ProbQuery;
import opendial.inference.queries.UtilQuery;
import opendial.state.DialogueState;
import opendial.utils.CombinatoricsUtils;

public class UserSimulator extends Thread {

	// logger
	public static Logger log = new Logger("Simulator", Logger.Level.DEBUG);

	DialogueState systemState;

	Model<DecisionRule> rewardModel;

	DialogueState realState;
	
	boolean paused = false;

	long systemActionStamp = 0;
	
	int nbTurns = 0;
	
	boolean startup = true;
	
	double accReturn = 0;
	
	ProbDistribution asrScore;
	ProbDistribution a_uother;
	
	public UserSimulator(DialogueState systemState, Domain domain) throws DialException {
		this.systemState = systemState;
		this.realState = domain.getInitialState().copy();
		
		realState.setName("simulator");
		for (Model<?> model : domain.getModels()) {
			if (!(model.getModelType().equals(DecisionRule.class))) {
				realState.attachModule(model);
			}
			else {
				rewardModel = (Model<DecisionRule>)model;
			}
		}
	}
	
	public DialogueState getRealState() {
		return realState;
	}

	public void startSimulator() {

		realState.startState();

		this.start();
		
		try {
		asrScore = realState.getContent("asrScore", true);
		a_uother = realState.getContent("a_uother", true);
		}
		catch (DialException e) { e.printStackTrace() ; }
	}

	
	
	@Override
	public void run() {
		while (true) {
			try {
			while (!systemState.isStable() || paused) {
				Thread.sleep(50);
			}
			performTurn();
			}
			catch (Exception e) {
				log.warning("simulator error: " + e);
			}
		}
	}
	
	public void pause(boolean shouldBePaused) {
		paused = shouldBePaused;
	}

	public void performTurn() {

		try {	
			
			Assignment action = getSystemAction();		
		//	log.debug("system action: " + action);
			double returnValue = getReturn(action);
			systemState.addContent(new Assignment("r", returnValue), "simulator");

			if (startup) {
				log.debug("STARTING UP SIMULATOR...");
				action = new Assignment("a_m", "AskRepeat");
				startup = false;
			}
			else {
				log.debug("reward value: " + returnValue);
				accReturn += returnValue;						
			}
			
			nbTurns++;
			
			showParameterState();
			
			log.debug("--------");

		/**	if (systemState.getNetwork().hasChanceNode("a_u^p")) {
				log.debug("expected next user action: " + systemState.getContent("a_u^p", 
						true).toString().replace("\n", ", "));
			} */
			realState.addContent(asrScore, "renew1");
			realState.addContent(a_uother, "renew2");
			
			realState.addContent(action, "systemAction");
			
			String error = "ERROR";
			if (systemState.getNetwork().hasChanceNode(error)) {
				log.debug("===> ERROR: " + systemState.getContent(error, true));	
			}
			
	//		log.debug("K-L divergence: " + getKLDivergence());
			
			Assignment sampled = sampleNextState(action);
			log.debug("Elements sampled from simulation: " + sampled);
			
			if (realState.getNetwork().hasChanceNode("floor")) {
				double prob = realState.getContent("floor", true).toDiscrete().getProb(
						new Assignment(), new Assignment("floor", "start"));
				if (prob > 0.98) {
					log.debug("accumulated return: " + accReturn);
					accReturn = 0;
				}
			}
			
			DiscreteProbDistribution obs = getNextObservation(sampled);
			Assignment evidence = new Assignment();
			evidence.addPair("i_u", sampled.getValue("i_u"));
			evidence.addPair("perceived", sampled.getValue("perceived"));
			evidence.addPair("carried", sampled.getValue("carried"));
		
			realState.addContent(evidence, "evidence");
			log.debug("==> Adding observation: " + obs.toString().replace("\n", ", "));
			log.debug("waiting for system processing...");
		
	//		systemState.addContent(realState.getContent("a_uother", true), "sim2");
			systemState.addContent(obs, "sim1");
			if (systemState.getNetwork().hasChanceNode("i_u")) {
				log.debug("i_u after system action: " + systemState.getContent("i_u", true)
						.toString().replace("\n", ", "));
			}
			
		}
		catch (Exception e) {
			log.warning("could not update simulator: " + e.toString());
			e.printStackTrace();
		}
	}


	private Assignment getSystemAction() throws DialException {
		if (systemState.getNetwork().hasChanceNode("a_m") && 
				systemState.isUpdated("a_m", systemActionStamp)) {
			systemActionStamp = System.currentTimeMillis();
			SimpleTable actionDistrib = systemState.getContent("a_m", true).
					toDiscrete().getProbTable(new Assignment());
			if (actionDistrib.getRows().size() ==1) {
				return actionDistrib.getRows().iterator().next();
			}
		}
		return new Assignment("a_m", ValueFactory.none());
	}

	
	
	private double getKLDivergence() throws DialException {
		SimpleTable expectedDis = systemState.getContent("a_u^p", true).toDiscrete().getProbTable(new Assignment());
		SimpleTable realDis = realState.getContent("a_u^p", true).toDiscrete().getProbTable(new Assignment());
		double distance = 0.0;
		for (Assignment a : expectedDis.getRows()) {
			 distance += Math.log(realDis.getProb(a) / expectedDis.getProb(a)) * realDis.getProb(a);
		}
		return distance;
	}
	

	private void showParameterState() throws DialException {
		if (nbTurns == 5) {
			for (int i = 1 ; i < 15 ;i++) {
				if (systemState.getNetwork().hasChanceNode("theta_"+i)) {
					log.debug("===> estimate for theta_"+i+": " + systemState.getContent("theta_"+i, true));						
				}
			}
			String fullTheta1 = "theta_(a_m=AskRepeat^i_u=Move(Left))";
			if (systemState.getNetwork().hasChanceNode(fullTheta1)) {
				log.debug("===> estimate for " + fullTheta1 +": " + systemState.getContent(fullTheta1, true));
			}
			String fullTheta2 = "theta_(a_m=AskRepeat^i_u=Move(Forward))";
			if (systemState.getNetwork().hasChanceNode(fullTheta1)) {
				log.debug("===> estimate for " + fullTheta2 +": " + systemState.getContent(fullTheta2, true));	
			}
			String fullTheta3 = "theta_(a_m=Confirm(Move(Left))^i_u=Move(Left))";
			if (systemState.getNetwork().hasChanceNode(fullTheta3)) {
				log.debug("===> estimate for " + fullTheta3 +": " + systemState.getContent(fullTheta3, true));
			}
			String fullTheta4 = "theta_(a_m=AskRepeat^i_u=Move(Right))";
			if (systemState.getNetwork().hasChanceNode(fullTheta4)) {
				log.debug("===> estimate for " + fullTheta4 +": " + systemState.getContent(fullTheta4, true));	
			}
			String fullTheta5 = "theta_(a_m=Do(*)^i_u=WhatDoYouSee)";
			if (systemState.getNetwork().hasChanceNode(fullTheta5)) {
				log.debug("===> estimate for " + fullTheta5 +": " + systemState.getContent(fullTheta5, true));	
			}
			String linearTheta1 = "theta_(i_u=Move(Left)^a_u=Move(Left))";
			if (systemState.getNetwork().hasChanceNode(linearTheta1)) {
				log.debug("===> estimate for " + linearTheta1 +": " + systemState.getContent(linearTheta1, true));	
			}
			String linearTheta2 = "theta_(a_m=AskRepeat^a_u=Move(Left))";
			if (systemState.getNetwork().hasChanceNode(linearTheta2)) {
				log.debug("===> estimate for " + linearTheta2 +": " + systemState.getContent(linearTheta2, true));	
			}
			String sarsaTheta1 = "theta_(movements1)";
			if (systemState.getNetwork().hasChanceNode(sarsaTheta1)) {
				log.debug("===> estimate for " + sarsaTheta1 +": " + systemState.getContent(sarsaTheta1, true));	
			}
			String sarsaTheta2 = "theta_(repeat)";
			if (systemState.getNetwork().hasChanceNode(sarsaTheta2)) {
				log.debug("===> estimate for " + sarsaTheta2 +": " + systemState.getContent(sarsaTheta2, true));	
			}
			String sarsaTheta3 = "theta_(confirm1)";
			if (systemState.getNetwork().hasChanceNode(sarsaTheta3)) {
				log.debug("===> estimate for " + sarsaTheta3 +": " + systemState.getContent(sarsaTheta3, true));	
			}
			String sarsaTheta4 = "theta_(confirm2)";
			if (systemState.getNetwork().hasChanceNode(sarsaTheta4)) {
				log.debug("===> estimate for " + sarsaTheta4 +": " + systemState.getContent(sarsaTheta4, true));	
			}
			String sarsaTheta5 = "theta_(doyousee1)";
			if (systemState.getNetwork().hasChanceNode(sarsaTheta5)) {
				log.debug("===> estimate for " + sarsaTheta5 +": " + systemState.getContent(sarsaTheta5, true));	
			}
			String sarsaTheta6 = "theta_(doyousee2)";
			if (systemState.getNetwork().hasChanceNode(sarsaTheta6)) {
				log.debug("===> estimate for " + sarsaTheta6 +": " + systemState.getContent(sarsaTheta6, true));	
			}
			String sarsaTheta7 = "theta_(wrong1)";
			if (systemState.getNetwork().hasChanceNode(sarsaTheta7)) {
				log.debug("===> estimate for " + sarsaTheta7 +": " + systemState.getContent(sarsaTheta7, true));	
			}
			String sarsaTheta8 = "theta_(pickup1)";
			if (systemState.getNetwork().hasChanceNode(sarsaTheta8)) {
				log.debug("===> estimate for " + sarsaTheta8 +": " + systemState.getContent(sarsaTheta8, true));	
			}
			nbTurns = 0;
		}
	}
	private Assignment sampleNextState(Assignment action) throws DialException {
		
		Assignment sampled = new Assignment();
		List<BNode> sequence = realState.getNetwork().getSortedNodes();
		Collections.reverse(sequence);
		for (BNode n : sequence) {
			if (n instanceof ChanceNode && !n.getId().equals("a_u^p") && !(n instanceof ProbabilityRuleNode)) {
				Value val = ((ChanceNode)n).sample(sampled);
				sampled.addPair(n.getId(), val);
			}	
		}
		return sampled;
	}
	
	
	private DiscreteProbDistribution getNextObservation(Assignment sampled) throws DialException {
		
		try {
	//		log.debug("sampled: " + sampled);
			InferenceAlgorithm algo = Settings.getInstance().inferenceAlgorithm.newInstance();
			ProbQuery query = new ProbQuery(realState.getNetwork(), Arrays.asList("perceived", "carried", "a_u^p"), sampled);
			ProbDistribution distrib = algo.queryProb(query);
			distrib.modifyVarId("a_u^p", "a_u");
	//		log.debug("resulting distrib: " + distrib);
			
			return distrib.toDiscrete();
		}
		catch (Exception e) {
			throw new DialException("cannot extract next observation: " + e);
		}
	}
	

	
	private double getReturn(Assignment action) throws DialException {
		DialogueState tempState = realState.copy();
		tempState.removeAllModules();
		tempState.attachModule(rewardModel);
		
		try {
			tempState.addContent(action, "systemAction");
			InferenceAlgorithm algo = Settings.getInstance().inferenceAlgorithm.newInstance();
			UtilityTable utilDistrib = algo.queryUtil(new UtilQuery(tempState, new LinkedList<String>()));
			return utilDistrib.getUtil(new Assignment());
		}
		catch (Exception e) {
			log.warning("could not extract return: " + e);
		}
		return 0.0;
	}



	final class StateUpdater extends Thread {
		
		DialogueState state;
		SimpleTable table;
		
		public StateUpdater(DialogueState state, SimpleTable table) {
			this.state = state;
			this.table = table;
		}
		
		public void run() {
			try {
				Thread.sleep(100);
			state.addContent(table, "GUI");
			}
			catch (Exception e) {
				log.warning("cannot update state with user utterance");
			}
		}
	}
	
}

