package alternatingcoevolution;

import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;

import org.dom4j.Document;
import org.dom4j.DocumentFactory;
import org.dom4j.Element;
import org.dom4j.Node;

import core.AbstractMethod;
import core.AbstractRepresentation;
import core.ComponentType;
import core.ComponentXMLData;
import core.ProblemXMLData;
import core.XMLFieldEntry;
import core.XMLMethodStep;
import main.FrevoMain;
import predatorprey.PredatorPrey;
import utils.NESRandom;
import utils.StatKeeper;

/**
 * FREVO Method plugin — Delta: Multi-Opponent Alternating Co-evolution.
 *
 * Configurable per the requested experiment:
 *   - generationsPerSide : switch interval (generations each side evolves
 *                          before swapping) — same knob as gamma.
 *   - opponentSampleSize : number of opponent candidates (K) frozen at each
 *                          swap and evaluated against, per candidate.
 *
 * COST WARNING: each candidate now costs K simulation evaluations instead
 * of 1 (K = opponentSampleSize), so total runtime scales roughly linearly
 * with K compared to gamma for the same totalGenerations/populationSize.
 *
 * Everything else (elitism, tournament selection, mutation-only variation,
 * XML snapshotting, setLastResults/loadFromXML wiring) is identical to the
 * confirmed-working gamma implementation — see AlternatingCoevolution.java
 * for the source-verified API notes that apply equally here.
 */
public class AlternatingCoevolution extends AbstractMethod {

    // ── configuration (read from this Method's .icomponent properties) ─────────
    private int    populationSize      = 40;
    private int    generationsPerSide  = 12;   // switch interval, same meaning as gamma
    private int    opponentSampleSize  = 3;    // K — number of frozen opponents sampled per swap
    private int    totalGenerations    = 240;
    private double eliteFraction       = 0.10;
    private float  mutationSeverity    = 0.30f;
    private float  mutationProbability = 0.10f;
    private int    tournamentSize      = 3;
    private int    saveInterval        = 1;

    // ── state ────────────────────────────────────────────────────────────────
    private ArrayList<AbstractRepresentation> preyPop;
    private ArrayList<AbstractRepresentation> predatorPop;
    private List<AbstractRepresentation> preyOpponentSample     = new ArrayList<>(); // frozen sample, opponents for predator side
    private List<AbstractRepresentation> predatorOpponentSample = new ArrayList<>(); // frozen sample, opponents for prey side

    private StatKeeper preyFitnessStat;
    private StatKeeper predatorFitnessStat;

    private ComponentXMLData representationData;

    public AlternatingCoevolution(NESRandom random) {
        super(random);
    }

    @Override
    public void runOptimization(ProblemXMLData problemData,
            ComponentXMLData representationData, ComponentXMLData rankingData,
            Hashtable<String, XMLFieldEntry> properties) {

        this.representationData = representationData;
        readConfig(properties);

        NESRandom random = getRandom();
        int numInputs  = problemData.getRequiredNumberOfInputs();
        int numOutputs = problemData.getRequiredNumberOfOutputs();

        preyPop     = newRandomPopulation(numInputs, numOutputs, populationSize, random);
        predatorPop = newRandomPopulation(numInputs, numOutputs, populationSize, random);

        preyFitnessStat     = new StatKeeper(true, "Prey best fitness ("     + FrevoMain.getCurrentRun() + ")", "Generations");
        predatorFitnessStat = new StatKeeper(true, "Predator best fitness (" + FrevoMain.getCurrentRun() + ")", "Generations");
        FrevoMain.addStatistics(preyFitnessStat, true);
        FrevoMain.addStatistics(predatorFitnessStat, true);

        boolean evolvingPreySide = true; // Round 1 convention: prey vs random predator first

        int generation = 0;
        while (generation < totalGenerations) {

            int sideGenerations = Math.min(generationsPerSide, totalGenerations - generation);

            for (int g = 0; g < sideGenerations; g++, generation++) {

                setProgress((float) generation / (float) totalGenerations);

                ArrayList<AbstractRepresentation> activePop =
                        evolvingPreySide ? preyPop : predatorPop;
                List<AbstractRepresentation> frozenOpponents =
                        evolvingPreySide ? predatorOpponentSample : preyOpponentSample;

                evaluatePopulationAgainstSample(activePop, evolvingPreySide, frozenOpponents, problemData);
                sortByFitnessDescending(activePop);

                double bestFitness = activePop.get(0).getFitness();
                (evolvingPreySide ? preyFitnessStat : predatorFitnessStat).add(bestFitness);

                System.out.println("[DeltaCoevolution] "
                        + (evolvingPreySide ? "PREY" : "PREDATOR")
                        + " gen " + generation + " best=" + bestFitness
                        + " (avg over " + frozenOpponents.size() + " opponents)");

                String side = evolvingPreySide ? "prey" : "predator";
                Element populationsXml = buildPopulationsElement(generation, activePop, bestFitness);
                String fileName = problemData.getName() + "_" + side + "_g"
                        + String.format("%03d", generation) + " (" + bestFitness + ")";

                setLastResults(new XMLMethodStep(fileName, populationsXml, this.seed, getRandom().getSeed()));

                if (saveInterval != 0 && generation % saveInterval == 0) {
                    FrevoMain.saveResult(fileName, populationsXml, this.seed, getRandom().getSeed());
                }

                if (handlePause()) {
                    setProgress(100);
                    return;
                }

                if (generation != totalGenerations - 1) {
                    evolvePopulation(activePop, random);
                }
            }

            // Snapshot the TOP-K individuals of the side that just finished
            // (population is already sorted descending from the last
            // generation's evaluation, so this is just the first K). Each
            // is deep-cloned via an XML round-trip so later mutation of the
            // live population can't retroactively change the frozen sample.
            ArrayList<AbstractRepresentation> justFinished = evolvingPreySide ? preyPop : predatorPop;
            List<AbstractRepresentation> newSample = new ArrayList<>();
            int sampleCount = Math.min(opponentSampleSize, justFinished.size());
            for (int i = 0; i < sampleCount; i++) {
                newSample.add(cloneRepresentation(justFinished.get(i)));
            }

            if (evolvingPreySide) {
                preyOpponentSample = newSample;
            } else {
                predatorOpponentSample = newSample;
            }

            evolvingPreySide = !evolvingPreySide;
        }

        setProgress(100);
    }

    @Override
    public void continueOptimization(ProblemXMLData problemData,
            ComponentXMLData representationData, ComponentXMLData rankingData,
            Hashtable<String, XMLFieldEntry> properties, Document doc) {
        System.out.println("[DeltaCoevolution] continueOptimization is not supported; "
                + "start a new run instead.");
    }

    @Override
    public ArrayList<ArrayList<AbstractRepresentation>> loadFromXML(Document doc) {
        ArrayList<ArrayList<AbstractRepresentation>> populationsResult = new ArrayList<>();

        Node populationsNode = doc.selectSingleNode("/frevo/populations");
        if (populationsNode == null) {
            return populationsResult;
        }

        List<? extends Node> populationNodes = populationsNode.selectNodes(".//population");

        for (Node populationNode : populationNodes) {
            ArrayList<AbstractRepresentation> populationResult = new ArrayList<>();
            List<?> representationNodes = populationNode.selectNodes("./*");

            for (Object o : representationNodes) {
                Node repNode = (Node) o;

                ComponentXMLData representation = FrevoMain
                        .getSelectedComponent(ComponentType.FREVO_REPRESENTATION);
                AbstractRepresentation member;
                try {
                    member = representation.getNewRepresentationInstance(0, 0, null);
                } catch (Exception e) {
                    throw new RuntimeException("[DeltaCoevolution] Failed to instantiate representation while loading", e);
                }
                member.loadFromXML(repNode);
                populationResult.add(member);
            }

            populationsResult.add(populationResult);
        }

        return populationsResult;
    }

    // ── config ───────────────────────────────────────────────────────────────

    private void readConfig(Hashtable<String, XMLFieldEntry> properties) {
        populationSize       = intOr(properties, "populationSize",       populationSize);
        generationsPerSide    = intOr(properties, "generationsPerSide",   generationsPerSide);
        opponentSampleSize    = intOr(properties, "opponentSampleSize",   opponentSampleSize);
        totalGenerations      = intOr(properties, "totalGenerations",     totalGenerations);
        eliteFraction         = dblOr(properties, "eliteFraction",        eliteFraction);
        mutationSeverity      = fltOr(properties, "mutationSeverity",     mutationSeverity);
        mutationProbability   = fltOr(properties, "mutationProbability",  mutationProbability);
        tournamentSize        = intOr(properties, "tournamentSize",       tournamentSize);
        saveInterval          = intOr(properties, "saveInterval",         saveInterval);
    }

    private static int intOr(Hashtable<String, XMLFieldEntry> p, String k, int def) {
        XMLFieldEntry e = p.get(k);
        return e != null ? Integer.parseInt(e.getValue()) : def;
    }

    private static double dblOr(Hashtable<String, XMLFieldEntry> p, String k, double def) {
        XMLFieldEntry e = p.get(k);
        return e != null ? Double.parseDouble(e.getValue()) : def;
    }

    private static float fltOr(Hashtable<String, XMLFieldEntry> p, String k, float def) {
        XMLFieldEntry e = p.get(k);
        return e != null ? Float.parseFloat(e.getValue()) : def;
    }

    // ── population management ────────────────────────────────────────────────

    private ArrayList<AbstractRepresentation> newRandomPopulation(
            int numInputs, int numOutputs, int size, NESRandom random) {
        ArrayList<AbstractRepresentation> pop = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            pop.add(newRandomRepresentation(numInputs, numOutputs, random));
        }
        return pop;
    }

    private AbstractRepresentation newRandomRepresentation(int numInputs, int numOutputs, NESRandom random) {
        try {
            return representationData.getNewRepresentationInstance(numInputs, numOutputs, random);
        } catch (Exception e) {
            throw new RuntimeException("[DeltaCoevolution] Failed to create a new representation instance", e);
        }
    }

    private AbstractRepresentation cloneRepresentation(AbstractRepresentation source) {
        try {
            Element scratch = DocumentFactory.getInstance().createElement("scratch");
            source.exportToXmlElement(scratch);
            AbstractRepresentation clone = representationData.getNewRepresentationInstance(0, 0, null);
            clone.loadFromXML(scratch.elements().get(0));
            return clone;
        } catch (Exception e) {
            throw new RuntimeException("[DeltaCoevolution] Failed to clone a representation", e);
        }
    }

    /**
     * Evaluates every candidate against EACH opponent in the frozen sample
     * (reusing PredatorPrey's existing single-opponent evaluate() machinery
     * once per opponent, exactly as gamma does — no changes to PredatorPrey
     * needed), and stores the AVERAGE fitness across the sample.
     *
     * If the sample is empty (e.g. the very first turn, before either side
     * has produced a snapshot yet), falls back to a single evaluation
     * against a null opponent — same random-opponent fallback gamma uses
     * for its first turn.
     */
    private void evaluatePopulationAgainstSample(ArrayList<AbstractRepresentation> pop,
            boolean evolvingPrey, List<AbstractRepresentation> opponentSample, ProblemXMLData problemData) {

        List<AbstractRepresentation> opponents = opponentSample.isEmpty()
                ? java.util.Collections.singletonList((AbstractRepresentation) null)
                : opponentSample;

        for (AbstractRepresentation member : pop) {
            double totalFitness = 0.0;
            for (AbstractRepresentation opponent : opponents) {
                PredatorPrey.setCoevolutionState(evolvingPrey, opponent);
                PredatorPrey problem = new PredatorPrey();
                problem.setProperties(problemData.getProperties());
                totalFitness += problem.evaluate(member);
            }
            member.setFitness(totalFitness / opponents.size());
        }
    }

    private void sortByFitnessDescending(ArrayList<AbstractRepresentation> pop) {
        pop.sort((a, b) -> Double.compare(b.getFitness(), a.getFitness()));
    }

    private void evolvePopulation(ArrayList<AbstractRepresentation> pop, NESRandom random) {
        int size = pop.size();
        int eliteCount = Math.max(1, (int) Math.round(size * eliteFraction));

        ArrayList<AbstractRepresentation> nextGen = new ArrayList<>();

        for (int i = 0; i < eliteCount && i < size; i++) {
            nextGen.add(cloneRepresentation(pop.get(i)));
        }

        while (nextGen.size() < size) {
            AbstractRepresentation parent = tournamentSelect(pop, random);
            AbstractRepresentation child = cloneRepresentation(parent);
            mutate(child, random);
            nextGen.add(child);
        }

        pop.clear();
        pop.addAll(nextGen);
    }

    private AbstractRepresentation tournamentSelect(ArrayList<AbstractRepresentation> pop, NESRandom random) {
        AbstractRepresentation best = pop.get(random.nextInt(pop.size()));
        for (int i = 1; i < tournamentSize; i++) {
            AbstractRepresentation contender = pop.get(random.nextInt(pop.size()));
            if (contender.getFitness() > best.getFitness()) {
                best = contender;
            }
        }
        return best;
    }

    private void mutate(AbstractRepresentation rep, NESRandom random) {
        int numFns = rep.getNumberofMutationFunctions();
        if (numFns > 0) {
            int method = random.nextInt(numFns);
            rep.mutate(mutationSeverity, mutationProbability, method);
        }
    }

    // ── XML snapshot ─────────────────────────────────────────────────────────

    private Element buildPopulationsElement(int generation,
            ArrayList<AbstractRepresentation> pop, double bestFitness) {

        Element root = DocumentFactory.getInstance().createElement("populations");
        root.addAttribute("count", "1");
        root.addAttribute("generation", String.valueOf(generation));
        root.addAttribute("randomseed", String.valueOf(getSeed()));
        root.addAttribute("best_fitness", String.valueOf(bestFitness));

        Element popElement = root.addElement("population");
        for (AbstractRepresentation member : pop) {
            member.exportToXmlElement(popElement);
        }

        return root;
    }
}