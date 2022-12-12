package edu.hm.hafner.metric;

import java.io.Serializable;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.Fraction;

import edu.umd.cs.findbugs.annotations.CheckReturnValue;

/**
 * A leaf in the tree. A leaf is a non-divisible coverage metric like line, instruction or branch coverage or mutation
 * or complexity.
 *
 * @author Ullrich Hafner
 */
public abstract class Value implements Serializable {
    private static final long serialVersionUID = -1062406664372222691L;
    private static final String METRIC_SEPARATOR = ":";

    public static Value getValue(final Metric metric, final List<? extends Value> values) {
        return findValue(metric, values)
                .orElseThrow(() -> new NoSuchElementException("No value for metric " + metric + " in " + values));
    }

    public static Optional<? extends Value> findValue(final Metric metric, final List<? extends Value> values) {
        return values.stream()
                .filter(v -> metric.equals(v.getMetric()))
                .findAny();
    }

    /**
     * Creates a new {@link Value} instance from the provided string representation. The string representation is
     * expected start with the metric, written in all caps characters and followed by a colon. Then the {@link Value}
     * specific serialization is following. Whitespace characters will be ignored.
     *
     * <p>Examples: LINE: 10/100, BRANCH: 0/5, COMPLEXITY: 160</p>
     *
     * @param stringRepresentation
     *         string representation to convert from
     *
     * @return the created value
     * @throws IllegalArgumentException
     *         if the string is not a valid cov instance
     */
    public static Value valueOf(final String stringRepresentation) {
        try {
            String cleanedFormat = StringUtils.deleteWhitespace(stringRepresentation);
            if (StringUtils.contains(cleanedFormat, METRIC_SEPARATOR)) {
                var metric = Metric.valueOf(StringUtils.substringBefore(cleanedFormat, METRIC_SEPARATOR));
                var value = StringUtils.substringAfter(cleanedFormat, METRIC_SEPARATOR);
                switch (metric) {
                    case CONTAINER:
                    case MODULE:
                    case PACKAGE:
                    case FILE:
                    case CLASS:
                    case METHOD:
                    case LINE:
                    case INSTRUCTION:
                    case BRANCH:
                       return Coverage.valueOf(metric, value);
                    case COMPLEXITY_DENSITY:
                       return new FractionValue(metric, Fraction.getFraction(value));
                    case COMPLEXITY:
                        return new CyclomaticComplexity(Integer.parseInt(value));
                    case LOC:
                        return new LinesOfCode(Integer.parseInt(value));
                    case MUTATION:
                    default:
                        throw new IllegalStateException(String.format("Metric %s is not supported yet", metric));
                }
            }
        }
        catch (NumberFormatException exception) {
            // ignore and throw a specific exception
        }
        throw new IllegalArgumentException(
                String.format("Cannot convert '%s' to a valid Value instance.", stringRepresentation));
    }

    private final Metric metric;

    /**
     * Creates a new leaf with the given coverage for the specified metric.
     *
     * @param metric
     *         the coverage metric
     */
    protected Value(final Metric metric) {
        this.metric = metric;
    }

    public final Metric getMetric() {
        return metric;
    }

    /**
     * Add the coverage from the specified instance to the coverage of this instance.
     *
     * @param other
     *         the additional coverage details
     *
     * @return the sum of this and the additional coverage
     */
    @CheckReturnValue
    public abstract Value add(Value other);

    /**
     * Computes the delta of this value with the specified value.
     *
     * @param other
     *         the value to compare with
     *
     * @return the delta of this and the additional value
     */
    @CheckReturnValue
    public abstract Fraction delta(Value other);

    /**
     * Merge this coverage with the specified coverage.
     *
     * @param other
     *         the other coverage
     *
     * @return the merged coverage
     * @throws IllegalArgumentException
     *         if the totals
     */
    @CheckReturnValue
    public abstract Value max(Value other);

    // FIXME Comment
    public abstract boolean isBelowThreshold(double threshold);

    /**
     * Serializes this instance into a String.
     *
     * @return a String serialization of this value
     */
    public abstract String serialize();

    /**
     * Returns whether this value has the same metric as the specified value.
     *
     * @param other
     *         the other value to compare with
     *
     * @return {@code true} if this value  has the same metric as the specified value, {@code false} otherwise
     */
    protected boolean hasSameMetric(final Value other) {
        return other.getMetric().equals(getMetric());
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Value value = (Value) o;
        return Objects.equals(metric, value.metric);
    }

    @Override
    public int hashCode() {
        return Objects.hash(metric);
    }
}