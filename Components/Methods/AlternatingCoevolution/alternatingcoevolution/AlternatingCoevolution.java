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
 * FREVO Method plugin — Alternating Predator/Prey Co-evolution.
 *
 * Runs BOTH sides of the predator-prey co-evolution automatically within a
 * single Run: it owns two independent populations (predator, prey) and
 * alternates which one is actively evolving every generationsPerSide
 * generations. Whichever side just finished its turn has its current best
 * genome frozen as a snapshot and handed to the OTHER side as its fixed
 * opponent for its next turn — entirely in memory, via
 * PredatorPrey.setCoevolutionState(...). No .zre round-tripping, no manual
 * restarts between rounds.
 *
 * This is intentionally tied to the predatorprey.PredatorPrey problem
 * specifically (not a generic Method) — the whole point is "two coupled
 * populations of the same representation shape, competing" which isn't a
 * meaningful concept for an arbitrary single-population FREVO problem.
 *
 * ── What this class is confident about ──────────────────────────────────────
 * - Population management (elitism, tournament selection) is hand-written
 *   here, not borrowed from NNGA/CEA2D internals, so there's nothing to
 *   guess about there.
 * - Representation creation/cloning uses representationData's factory and
 *   exportToXmlElement/loadFromXML, matching the pattern FREVO's own CEA2D
 *   Method uses for exactly the same purpose.
 * - Scoring a candidate goes straight through PredatorPrey.evaluate(...)
 *   (see PredatorPrey.java), bypassing FREVO's Ranking abstraction — this
 *   avoids guessing at an API this class never confirmed from source.
 * - Mutation uses the confirmed real API: AbstractRepresentation's own
 *   getNumberofMutationFunctions() / mutate(float severity, float
 *   probability, int method), verified via Eclipse autocomplete against
 *   the real compiled FREVO jar. There is no crossover step — no
 *   confirmed recombine/crossover method exists on AbstractRepresentation
 *   (only a generic named-function dispatcher whose function names
 *   aren't known), so this is a mutation-only GA. That's a legitimate,
 *   standard simplification for neuroevolution of continuous weight
 *   vectors, not a missing feature.
 */
public class AlternatingCoevolution extends AbstractMethod {

    // ── configuration (read from this Method's .icomponent properties) ─────────
    private int    populationSize      = 40;
    private int    generationsPerSide  = 20;   // "medium" — a full swap cycle is 2x this
    private int    totalGenerations    = 500;  // ~10 swaps each way by default
    private double eliteFraction       = 0.10;
    private float  mutationSeverity    = 0.30f; // how strongly a mutated weight is perturbed
    private float  mutationProbability = 0.10f; // per-weight chance of mutation (passed into rep.mutate())
    private int    tournamentSize      = 3;
    private int    saveInterval        = 1;    // save every generation by default

    // ── state ────────────────────────────────────────────────────────────────
    private ArrayList<AbstractRepresentation> preyPop;
    private ArrayList<AbstractRepresentation> predatorPop;
    private AbstractRepresentation preyChampion     = null; // frozen snapshot, opponent for predator side
    private AbstractRepresentation predatorChampion = null; // frozen snapshot, opponent for prey side

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

                ArrayList<AbstractRepresentation> activePopulation =
                        evolvingPreySide ? preyPop : predatorPop;
                AbstractRepresentation frozenOpponent =
                        evolvingPreySide ? predatorChampion : preyChampion;

                // Hand the live opponent object to the Problem for this generation.
                PredatorPrey.setCoevolutionState(evolvingPreySide, frozenOpponent);

                evaluatePopulation(activePopulation, problemData);
                sortByFitnessDescending(activePopulation);

                double bestFitness = activePopulation.get(0).getFitness();
                (evolvingPreySide ? preyFitnessStat : predatorFitnessStat).add(bestFitness);

                System.out.println("[AlternatingCoevolution] "
                        + (evolvingPreySide ? "PREY" : "PREDATOR")
                        + " gen " + generation + " best=" + bestFitness);

                // Build this generation's XML snapshot ONCE, then:
                //   (a) always register it via setLastResults() — this is
                //       what actually enables FREVO's Replay/Save buttons,
                //       confirmed from AbstractMethod's real source (NNGA
                //       calls this every generation, regardless of whether
                //       it also writes to disk that generation).
                //   (b) only write it to disk when saveInterval says to.
                String side = evolvingPreySide ? "prey" : "predator";
                Element populationsXml = buildPopulationsElement(generation, evolvingPreySide, activePopulation, bestFitness);
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
                    evolvePopulation(activePopulation, random);
                }
            }

            // Snapshot the side that just finished its turn — a true clone via
            // XML round-trip, so later mutation of the live population can't
            // retroactively change the frozen opponent the other side is using.
            ArrayList<AbstractRepresentation> justFinished = evolvingPreySide ? preyPop : predatorPop;
            AbstractRepresentation snapshot = cloneRepresentation(justFinished.get(0));
            if (evolvingPreySide) {
                preyChampion = snapshot;
            } else {
                predatorChampion = snapshot;
            }

            evolvingPreySide = !evolvingPreySide;
        }

        setProgress(100);
    }

    @Override
    public void continueOptimization(ProblemXMLData problemData,
            ComponentXMLData representationData, ComponentXMLData rankingData,
            Hashtable<String, XMLFieldEntry> properties, Document doc) {
        // Resuming a paused alternating-coevolution run isn't implemented —
        // this class only supports starting fresh via runOptimization().
        System.out.println("[AlternatingCoevolution] continueOptimization is not supported; "
                + "start a new run instead.");
    }

    @Override
    public ArrayList<ArrayList<AbstractRepresentation>> loadFromXML(Document doc) {
        // Real parsing logic, matching FREVO's own NNGA.loadFromXML exactly
        // (confirmed from source) — this is what File > Load reads to
        // populate the candidate list, independent of anything currently
        // held in memory from a live run.
        ArrayList<ArrayList<AbstractRepresentation>> populationsResult = new ArrayList<>();

        Node populationsNode = doc.selectSingleNode("/frevo/populations");
        if (populationsNode == null) {
            return populationsResult; // not a file this Method saved
        }

        List<? extends Node> populationNodes = populationsNode.selectNodes(".//population");

        for (Node populationNode : populationNodes) {
            ArrayList<AbstractRepresentation> populationResult = new ArrayList<>();
            List<?> representationNodes = populationNode.selectNodes("./*");

            for (Object o : representationNodes) {
                Node repNode = (Node) o;

                ComponentXMLData representation = (ComponentXMLData) main.FrevoMain
                        .getSelectedComponent(ComponentType.FREVO_REPRESENTATION);
                AbstractRepresentation member;
                try {
                    member = representation.getNewRepresentationInstance(0, 0, null);
                } catch (Exception e) {
                    throw new RuntimeException("[AlternatingCoevolution] Failed to instantiate representation while loading", e);
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
        populationSize         = intOr(properties, "populationSize",        populationSize);
        generationsPerSide     = intOr(properties, "generationsPerSide",    generationsPerSide);
        totalGenerations       = intOr(properties, "totalGenerations",      totalGenerations);
        eliteFraction          = dblOr(properties, "eliteFraction",         eliteFraction);
        mutationSeverity       = fltOr(properties, "mutationSeverity",      mutationSeverity);
        mutationProbability    = fltOr(properties, "mutationProbability",   mutationProbability);
        tournamentSize         = intOr(properties, "tournamentSize",        tournamentSize);
        saveInterval           = intOr(properties, "saveInterval",          saveInterval);
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

    // ── population management (hand-written — nothing FREVO-internal guessed here) ─

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
            throw new RuntimeException("[AlternatingCoevolution] Failed to create a new representation instance", e);
        }
    }

    /**
     * Deep-clones a representation via an XML round-trip: export the source
     * to a throwaway XML element, then load a brand-new instance from it.
     * This is the same mechanism CEA2D uses to reconstruct representations
     * from saved XML (see its private createList() helper), just used here
     * for in-memory cloning instead of loading from a file.
     */
    private AbstractRepresentation cloneRepresentation(AbstractRepresentation source) {
        try {
            Element scratch = DocumentFactory.getInstance().createElement("scratch");
            source.exportToXmlElement(scratch);
            AbstractRepresentation clone = representationData.getNewRepresentationInstance(0, 0, null);
            clone.loadFromXML(scratch.elements().get(0));
            return clone;
        } catch (Exception e) {
            throw new RuntimeException("[AlternatingCoevolution] Failed to clone a representation", e);
        }
    }

 
    private void evaluatePopulation(ArrayList<AbstractRepresentation> pop, ProblemXMLData problemData) {
        for (AbstractRepresentation member : pop) {
            PredatorPrey problem = new PredatorPrey();
            problem.setProperties(problemData.getProperties());
            double fitness = problem.evaluate(member);
            member.setFitness(fitness);
        }
    }

    private void sortByFitnessDescending(ArrayList<AbstractRepresentation> pop) {
        pop.sort((a, b) -> Double.compare(b.getFitness(), a.getFitness()));
    }

    private void evolvePopulation(ArrayList<AbstractRepresentation> pop, NESRandom random) {
        int size = pop.size();
        int eliteCount = Math.max(1, (int) Math.round(size * eliteFraction));

        ArrayList<AbstractRepresentation> nextGen = new ArrayList<>();

        // Elitism: carry the top performers through unchanged (cloned so
        // later mutation of "the same slot" next generation doesn't alias).
        for (int i = 0; i < eliteCount && i < size; i++) {
            nextGen.add(cloneRepresentation(pop.get(i)));
        }

        // Fill the rest via tournament selection + mutation.
        // NOTE: no crossover — AbstractRepresentation exposes no confirmed
        // recombine/crossover method (only a generic named-function
        // dispatcher whose function names aren't known), so this is a
        // mutation-only GA: tournament-select one parent, clone it, mutate
        // the clone. This is a standard, legitimate simplification for
        // neuroevolution of continuous weight vectors.
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

    /**
     * Confirmed via Eclipse autocomplete on AbstractRepresentation:
     *   mutate(float severity, float probability, int method)
     *   getNumberofMutationFunctions() : int   (note lowercase "of" — real casing)
     * severity/probability are read from this Method's own config
     * (mutationSeverity / mutationProbability properties); method is a
     * random pick among however many mutation strategies the loaded
     * representation type implements.
     */
    private void mutate(AbstractRepresentation rep, NESRandom random) {
        int numFns = rep.getNumberofMutationFunctions();
        if (numFns > 0) {
            int method = random.nextInt(numFns);
            rep.mutate(mutationSeverity, mutationProbability, method);
        }
    }

    // ── XML snapshot ─────────────────────────────────────────────────────────

    /**
     * Builds the <populations> element for one generation's snapshot,
     * matching the shape confirmed from FREVO's own NNGA source (same
     * count/generation/randomseed/best_fitness attributes; best_fitness
     * isn't in ISave.dtd's declared attribute list either, but NNGA's own
     * real saved files include it too, so the parser clearly tolerates it).
     */
    private Element buildPopulationsElement(int generation, boolean evolvingPreySide,
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