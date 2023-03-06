package edu.hm.hafner.metric;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.apache.commons.lang3.math.Fraction;

import edu.hm.hafner.metric.Coverage.CoverageBuilder;
import edu.hm.hafner.util.PathUtil;

/**
 * A {@link Node} for a specific file. It stores the actual file name along with the coverage information.
 *
 * @author Ullrich Hafner
 */
@SuppressWarnings("PMD.GodClass")
public final class FileNode extends Node {
    private static final long serialVersionUID = -3795695377267542624L;

    private final NavigableMap<Integer, Integer> coveredPerLine = new TreeMap<>();
    private final NavigableMap<Integer, Integer> missedPerLine = new TreeMap<>();

    private final SortedSet<Integer> modifiedLines = new TreeSet<>();
    private final NavigableMap<Integer, Integer> indirectCoverageChanges = new TreeMap<>();
    private final NavigableMap<Metric, Fraction> coverageDelta = new TreeMap<>();

    /**
     * Creates a new {@link FileNode} with the given name.
     *
     * @param name
     *         the human-readable name of the node
     */
    public FileNode(final String name) {
        super(Metric.FILE, name);
    }

    @Override
    public FileNode copy() {
        var file = new FileNode(getName());
        file.coveredPerLine.putAll(coveredPerLine);
        file.missedPerLine.putAll(missedPerLine);
        file.modifiedLines.addAll(modifiedLines);
        file.indirectCoverageChanges.putAll(indirectCoverageChanges);
        file.coverageDelta.putAll(coverageDelta);
        return file;
    }

    public SortedSet<Integer> getModifiedLines() {
        return modifiedLines;
    }

    /**
     * Returns whether this file has been modified in the active change set.
     *
     * @return {@code true} if this file has been modified in the active change set, {@code false} otherwise
     */
    @Override
    public boolean hasModifiedLines() {
        return !modifiedLines.isEmpty();
    }

    /**
     * Returns whether this file has been modified at the specified line.
     *
     * @param line
     *         the line to check
     *
     * @return {@code true} if this file has been modified at the specified line, {@code false} otherwise
     */
    public boolean hasModifiedLine(final int line) {
        return modifiedLines.contains(line);
    }

    /**
     * Marks the specified lines as being modified.
     *
     * @param lines
     *         the modified code lines
     */
    public void addModifiedLines(final int... lines) {
        for (int line : lines) {
            modifiedLines.add(line);
        }
    }

    @Override
    protected Optional<Node> filterTreeByModifiedLines() {
        if (!hasCoveredLinesInChangeSet()) {
            return Optional.empty();
        }

        var copy = new FileNode(getName());
        var lineCoverage = Coverage.nullObject(Metric.LINE);
        var lineBuilder = new CoverageBuilder().setMetric(Metric.LINE);
        var branchCoverage = Coverage.nullObject(Metric.BRANCH);
        var branchBuilder = new CoverageBuilder().setMetric(Metric.BRANCH);
        for (int line : getCoveredLinesOfChangeSet()) {
            var covered = coveredPerLine.getOrDefault(line, 0);
            var missed = missedPerLine.getOrDefault(line, 0);
            var total = covered + missed;
            copy.addCounters(line, covered, missed);
            if (total == 0) {
                throw new IllegalArgumentException("No coverage for line " + line);
            }
            else if (total == 1) {
                lineCoverage = lineCoverage.add(lineBuilder.setCovered(covered).setMissed(missed).build());
            }
            else {
                var branchCoveredAsLine = covered > 0 ? 1 : 0;
                lineCoverage = lineCoverage.add(
                        lineBuilder.setCovered(branchCoveredAsLine).setMissed(1 - branchCoveredAsLine).build());
                branchCoverage = branchCoverage.add(branchBuilder.setCovered(covered).setMissed(missed).build());
            }
            copy.addModifiedLines(line);
        }
        addLineAndBranchCoverage(copy, lineCoverage, branchCoverage);
        return Optional.of(copy);
    }

    @Override
    protected Optional<Node> filterTreeByModifiedFiles() {
        return hasCoveredLinesInChangeSet() ? Optional.of(copyTree()) : Optional.empty();
    }

    private static void addLineAndBranchCoverage(final FileNode copy, final Coverage lineCoverage,
            final Coverage branchCoverage) {
        if (lineCoverage.isSet()) {
            copy.addValue(lineCoverage);
        }
        if (branchCoverage.isSet()) {
            copy.addValue(branchCoverage);
        }
    }

    @Override
    @SuppressWarnings({"PMD.CyclomaticComplexity", "PMD.CognitiveComplexity"})
    protected Optional<Node> filterTreeByIndirectChanges() {
        if (!hasIndirectCoverageChanges()) {
            return Optional.empty();
        }

        var copy = new FileNode(getName());
        Coverage lineCoverage = Coverage.nullObject(Metric.LINE);
        Coverage branchCoverage = Coverage.nullObject(Metric.BRANCH);
        for (Map.Entry<Integer, Integer> change : getIndirectCoverageChanges().entrySet()) {
            int delta = change.getValue();
            Coverage currentCoverage = getBranchCoverage(change.getKey());
            if (!currentCoverage.isSet()) {
                currentCoverage = getLineCoverage(change.getKey());
            }
            var builder = new CoverageBuilder();
            if (delta > 0) {
                // the line is fully covered - even in case of branch coverage
                if (delta == currentCoverage.getCovered()) {
                    builder.setMetric(Metric.LINE).setCovered(1).setMissed(0);
                    lineCoverage = lineCoverage.add(builder.build());
                }
                // the branch coverage increased for 'delta' hits
                if (currentCoverage.getTotal() > 1) {
                    builder.setMetric(Metric.BRANCH).setCovered(delta).setMissed(0);
                    branchCoverage = branchCoverage.add(builder.build());
                }
            }
            else if (delta < 0) {
                // the line is not covered anymore
                if (currentCoverage.getCovered() == 0) {
                    builder.setMetric(Metric.LINE).setCovered(0).setMissed(1);
                    lineCoverage = lineCoverage.add(builder.build());
                }
                // the branch coverage is decreased by 'delta' hits
                if (currentCoverage.getTotal() > 1) {
                    builder.setMetric(Metric.BRANCH).setCovered(0).setMissed(Math.abs(delta));
                    branchCoverage = branchCoverage.add(builder.build());
                }
            }
        }
        addLineAndBranchCoverage(copy, lineCoverage, branchCoverage);

        return Optional.of(copy);
    }

    /**
     * Adds an indirect coverage change for a specific line.
     *
     * @param line
     *         The line with the coverage change
     * @param hitsDelta
     *         The delta of the coverage hits before and after the code changes
     */
    public void addIndirectCoverageChange(final int line, final int hitsDelta) {
        indirectCoverageChanges.put(line, hitsDelta);
    }

    public SortedMap<Integer, Integer> getIndirectCoverageChanges() {
        return new TreeMap<>(indirectCoverageChanges);
    }

    public NavigableSet<Integer> getLinesWithCoverage() {
        return new TreeSet<>(coveredPerLine.keySet());
    }

    /**
     * Returns whether this file has a coverage result for the specified line.
     *
     * @param line
     *         the line to check
     *
     * @return {@code true} if this file has a coverage result for the specified line, {@code false} otherwise
     */
    public boolean hasCoverageForLine(final int line) {
        return coveredPerLine.containsKey(line);
    }

    /**
     * Returns the line coverage result for the specified line.
     *
     * @param line
     *         the line to check
     *
     * @return the line coverage result for the specified line.
     */
    public Coverage getLineCoverage(final int line) {
        if (hasCoverageForLine(line)) {
            var covered = getCoveredOfLine(line) > 0 ? 1 : 0;
            return new CoverageBuilder().setMetric(Metric.LINE)
                    .setCovered(covered)
                    .setMissed(1 - covered)
                    .build();
        }
        return Coverage.nullObject(Metric.LINE);
    }

    /**
     * Returns the branch coverage result for the specified line.
     *
     * @param line
     *         the line to check
     *
     * @return the line coverage result for the specified line.
     */
    public Coverage getBranchCoverage(final int line) {
        if (hasCoverageForLine(line)) {
            var covered = getCoveredOfLine(line);
            var missed = getMissedOfLine(line);
            if (covered + missed > 1) {
                return new CoverageBuilder().setMetric(Metric.BRANCH)
                        .setCovered(covered)
                        .setMissed(missed)
                        .build();
            }
        }
        return Coverage.nullObject(Metric.BRANCH);
    }

    @Override
    public String getPath() {
        var pathUtil = new PathUtil();
        return mergePath(pathUtil.getAbsolutePath(getName()));
    }

    @Override
    public Set<String> getFiles() {
        return Set.of(getPath());
    }

    /**
     * Computes a delta coverage between this node and the given reference node.
     *
     * @param referenceNode
     *         the node to compare with this node
     */
    // TODO: wouldn't it make more sense to return an independent object?
    public void computeDelta(final FileNode referenceNode) {
        NavigableMap<Metric, Value> referenceCoverage = referenceNode.getMetricsDistribution();
        getMetricsDistribution().forEach((metric, value) -> {
            if (referenceCoverage.containsKey(metric)) {
                coverageDelta.put(metric, value.delta(referenceCoverage.get(metric)));
            }
        });
    }

    /**
     * Returns whether the coverage of this node is affected indirectly by the tests in the change set.
     *
     * @return {@code true} if this node is affected indirectly by the tests.
     */
    public boolean hasIndirectCoverageChanges() {
        return !indirectCoverageChanges.isEmpty();
    }

    /**
     * Returns the coverage percentage for modified code lines (for the specified metric).
     *
     * @param metric
     *         the metric to check
     *
     * @return the coverage percentage for modified code lines
     */
    public Fraction getModifiedLinesCoverage(final Metric metric) {
        return coverageDelta.getOrDefault(metric, Fraction.ZERO);
    }

    /**
     * Returns whether this file has coverage results for modified code lines (for the specified metric).
     *
     * @param metric
     *         the metric to check
     *
     * @return {@code true} has coverage results for modified code lines, {@code false} otherwise
     */
    public boolean hasModifiedLinesCoverage(final Metric metric) {
        return coverageDelta.containsKey(metric);
    }

    /**
     * Returns whether this file has coverage results for ch    anged code lines.
     *
     * @return {@code true} if this file has coverage results for modified code lines, {@code false} otherwise
     */
    public boolean hasModifiedLinesCoverage() {
        return hasModifiedLinesCoverage(Metric.LINE) || hasModifiedLinesCoverage(Metric.BRANCH);
    }

    /**
     * Returns the lines with code coverage that are part of the change set.
     *
     * @return the lines with code coverage that are part of the change set
     */
    public SortedSet<Integer> getCoveredLinesOfChangeSet() {
        SortedSet<Integer> coveredDelta = getLinesWithCoverage();
        coveredDelta.retainAll(getModifiedLines());
        return coveredDelta;
    }

    /**
     * Returns whether this file has lines with code coverage that are part of the change set.
     *
     * @return {@code true} if this file has lines with code coverage that are part of the change set, {@code false}
     *         otherwise.
     */
    public boolean hasCoveredLinesInChangeSet() {
        return !getCoveredLinesOfChangeSet().isEmpty();
    }

    /**
     * Returns the line coverage results for lines that are part of the change set.
     *
     * @return the line coverage results for lines that are part of the change set.
     */
    public List<Coverage> getCoverageOfChangeSet() {
        SortedSet<Integer> coveredDelta = getLinesWithCoverage();
        coveredDelta.retainAll(getModifiedLines());
        return coveredDelta.stream().map(this::getLineCoverage).collect(Collectors.toList());
    }

    /**
     * Add the coverage counters for the specified line.
     *
     * @param lineNumber
     *         the line number to add the counters for
     * @param covered
     *         the number of covered items
     * @param missed
     *         the number of missed items
     */
    public void addCounters(final int lineNumber, final int covered, final int missed) {
        coveredPerLine.put(lineNumber, covered);
        missedPerLine.put(lineNumber, missed);
    }

    public int[] getCoveredCounters() {
        return entriesToArray(coveredPerLine);
    }

    public int[] getMissedCounters() {
        return entriesToArray(missedPerLine);
    }

    /**
     * Returns the number of covered items for the specified line.
     *
     * @param line
     *         the line to check
     *
     * @return the number of covered items for the specified line
     */
    public int getCoveredOfLine(final int line) {
        return coveredPerLine.getOrDefault(line, 0);
    }

    /**
     * Returns the number of missed items for the specified line.
     *
     * @param line
     *         the line to check
     *
     * @return the number of missed items for the specified line
     */
    public int getMissedOfLine(final int line) {
        return missedPerLine.getOrDefault(line, 0);
    }

    private int[] entriesToArray(final NavigableMap<Integer, Integer> map) {
        return map.values().stream().mapToInt(i -> i).toArray();
    }

    /**
     * Returns the lines that have no line coverage. Note that lines that have no branch coverage are not included as
     * these are reported separately in {@link #getMissedBranches()}.
     *
     * @return the lines that have no line coverage
     */
    public NavigableSet<Integer> getMissedLines() {
        return getLinesWithCoverage().stream()
                .filter(line -> getCoveredOfLine(line) == 0 && getMissedOfLine(line) == 1)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /**
     * Returns the lines that have a branch coverage less than 100%. The returned map contains the line number as the
     * key and the number of missed branches as value.
     *
     * @return the mapping of not fully covered lines to the number of missed branches
     */
    public NavigableMap<Integer, Integer> getMissedBranches() {
        return getLinesWithCoverage().stream()
                .filter(line -> getCoveredOfLine(line) + getMissedOfLine(line) > 1)
                .filter(line -> getMissedOfLine(line) > 0)
                .collect(Collectors.toMap(line -> line, missedPerLine::get, (a, b) -> a, TreeMap::new));
    }

    public NavigableMap<Integer, Integer> getCounters() {
        return Collections.unmodifiableNavigableMap(coveredPerLine);
    }

    /**
     * Create a new class node with the given name and add it to the list of children.
     *
     * @param className
     *         the class name
     *
     * @return the created and linked class node
     */
    public ClassNode createClassNode(final String className) {
        var classNode = new ClassNode(className);
        addChild(classNode);
        return classNode;
    }

    /**
     * Searches for the specified class node. If the class node is not found then a new class node will be created and
     * linked to this file node.
     *
     * @param className
     *         the class name
     *
     * @return the created and linked class node
     * @see #createClassNode(String)
     */
    public ClassNode findOrCreateClassNode(final String className) {
        return findClass(className).orElseGet(() -> createClassNode(className));
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        if (!super.equals(o)) {
            return false;
        }
        FileNode fileNode = (FileNode) o;
        return Objects.equals(coveredPerLine, fileNode.coveredPerLine)
                && Objects.equals(missedPerLine, fileNode.missedPerLine)
                && Objects.equals(modifiedLines, fileNode.modifiedLines)
                && Objects.equals(indirectCoverageChanges, fileNode.indirectCoverageChanges)
                && Objects.equals(coverageDelta, fileNode.coverageDelta);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), coveredPerLine, missedPerLine, modifiedLines, indirectCoverageChanges,
                coverageDelta);
    }
}
