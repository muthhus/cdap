package io.cdap.cdap.etl.api.sql.engine.relation;


import io.cdap.cdap.etl.api.relational.Expression;
import io.cdap.cdap.etl.api.relational.ExtractableExpression;

import java.util.Collection;
import java.util.Objects;

public class SparkSQLExpression implements ExtractableExpression<String> {
  private final String expression;

  /**
   * Creates a {@link SparkSQLExpression} from the specified SQL string.
   * @param expression a String containing the SQL expression.
   */
  public SparkSQLExpression(String expression) {
    this.expression = expression;
  }

  /**
   *
   * @return the SQL string contained in the {@link SparkSQLExpression} object.
   */
  @Override
  public String extract() {
    return expression;
  }

  /**
   * A {@link SparkSQLExpression} is always assumed to contain valid SQL. It is the responsibility of the creator of the
   * object to ensure correctness of the SQL string.
   * @return This method always returns true.
   */
  @Override
  public boolean isValid() {
    return true;
  }

  /**
   * A {@link SparkSQLExpression} is always assumed to contain valid SQL. It is the responsibility of the creator of the
   * object to ensure correctness of the SQL string.
   * @return This method always returns a null string.
   */
  @Override
  public String getValidationError() {
    return null;
  }

  /**
   * Two {@link SparkSQLExpression} objects are considered equal if they contain equal SQL expressions.
   * @param o other object to be compared for equality.
   * @return true iff both objects are {@link SparkSQLExpression}s with equal SQL strings.
   */
  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    SparkSQLExpression that = (SparkSQLExpression) o;
    return Objects.equals(extract(), that.extract());
  }

  @Override
  public int hashCode() {
    return Objects.hash(extract());
  }

  /**
   * Check if an expression is valid
   */
  public static boolean supportsExpression(Expression expression) {
    return expression instanceof SparkSQLExpression && expression.isValid();
  }

  public static boolean supportsExpressions(Collection<Expression> expressions) {
    return expressions.stream().allMatch(SparkSQLExpression::supportsExpression);
  }
}
