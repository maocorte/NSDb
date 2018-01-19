package io.radicalbit.nsdb.web

import io.radicalbit.nsdb.common.statement._
import org.scalatest.{Matchers, WordSpec}

class QueryEnrichmentTest extends WordSpec with Matchers {

  "Query with a single filter over a dimension of Long type" should {
    "be correctly converted with equal operator" in {
      val filters = Seq(Filter("age", 1L, "="))
      val originalStatement = SelectSQLStatement("db",
                                                 "namespace",
                                                 "people",
                                                 ListFields(List(Field("name", None))),
                                                 None,
                                                 None,
                                                 None,
                                                 Some(LimitOperator(1)))

      val enrichedStatement = originalStatement.addConditions(filters.map(Filter.unapply(_).get))

      enrichedStatement shouldEqual
        SelectSQLStatement("db",
                           "namespace",
                           "people",
                           ListFields(List(Field("name", None))),
                           Some(Condition(EqualityExpression("age", 1L))),
                           None,
                           None,
                           Some(LimitOperator(1)))
    }
    "be correctly converted with GT operator" in {
      val filters = Seq(Filter("age", 1L, ">"))
      val originalStatement = SelectSQLStatement("db",
                                                 "namespace",
                                                 "people",
                                                 ListFields(List(Field("name", None))),
                                                 None,
                                                 None,
                                                 None,
                                                 Some(LimitOperator(1)))

      val enrichedStatement = originalStatement.addConditions(filters.map(Filter.unapply(_).get))

      enrichedStatement shouldEqual
        SelectSQLStatement(
          "db",
          "namespace",
          "people",
          ListFields(List(Field("name", None))),
          Some(Condition(ComparisonExpression("age", GreaterThanOperator, 1L))),
          None,
          None,
          Some(LimitOperator(1))
        )
    }
    "be correctly converted with GTE operator" in {
      val filters = Seq(Filter("age", 1L, ">="))
      val originalStatement = SelectSQLStatement("db",
                                                 "namespace",
                                                 "people",
                                                 ListFields(List(Field("name", None))),
                                                 None,
                                                 None,
                                                 None,
                                                 Some(LimitOperator(1)))

      val enrichedStatement = originalStatement.addConditions(filters.map(Filter.unapply(_).get))

      enrichedStatement shouldEqual
        SelectSQLStatement(
          "db",
          "namespace",
          "people",
          ListFields(List(Field("name", None))),
          Some(Condition(ComparisonExpression("age", GreaterOrEqualToOperator, 1L))),
          None,
          None,
          Some(LimitOperator(1))
        )
    }
    "be correctly converted with LT operator" in {
      val filters = Seq(Filter("age", 1L, "<"))
      val originalStatement = SelectSQLStatement("db",
                                                 "namespace",
                                                 "people",
                                                 ListFields(List(Field("name", None))),
                                                 None,
                                                 None,
                                                 None,
                                                 Some(LimitOperator(1)))

      val enrichedStatement = originalStatement.addConditions(filters.map(Filter.unapply(_).get))

      enrichedStatement shouldEqual
        SelectSQLStatement(
          "db",
          "namespace",
          "people",
          ListFields(List(Field("name", None))),
          Some(Condition(ComparisonExpression("age", LessThanOperator, 1L))),
          None,
          None,
          Some(LimitOperator(1))
        )
    }
    "be correctly converted with LTE operator" in {
      val filters = Seq(Filter("age", 1L, "<="))
      val originalStatement = SelectSQLStatement("db",
                                                 "namespace",
                                                 "people",
                                                 ListFields(List(Field("name", None))),
                                                 None,
                                                 None,
                                                 None,
                                                 Some(LimitOperator(1)))

      val enrichedStatement = originalStatement.addConditions(filters.map(Filter.unapply(_).get))

      enrichedStatement shouldEqual
        SelectSQLStatement(
          "db",
          "namespace",
          "people",
          ListFields(List(Field("name", None))),
          Some(Condition(ComparisonExpression("age", LessOrEqualToOperator, 1L))),
          None,
          None,
          Some(LimitOperator(1))
        )
    }
  }

  "Query with a single filter over a dimension of String type" should {
    "be correctly converted with equal operator" in {
      val filters = Seq(Filter("surname", "Poe", "="))
      val originalStatement = SelectSQLStatement("db",
                                                 "namespace",
                                                 "people",
                                                 ListFields(List(Field("name", None))),
                                                 None,
                                                 None,
                                                 None,
                                                 Some(LimitOperator(1)))

      val enrichedStatement = originalStatement.addConditions(filters.map(Filter.unapply(_).get))

      enrichedStatement shouldEqual
        SelectSQLStatement("db",
                           "namespace",
                           "people",
                           ListFields(List(Field("name", None))),
                           Some(Condition(EqualityExpression("surname", "Poe"))),
                           None,
                           None,
                           Some(LimitOperator(1)))
    }
    "be correctly converted with LIKE operator" in {
      val filters = Seq(Filter("surname", "Poe", "like"))
      val originalStatement = SelectSQLStatement("db",
                                                 "namespace",
                                                 "people",
                                                 ListFields(List(Field("name", None))),
                                                 None,
                                                 None,
                                                 None,
                                                 Some(LimitOperator(1)))

      val enrichedStatement = originalStatement.addConditions(filters.map(Filter.unapply(_).get))

      enrichedStatement shouldEqual
        SelectSQLStatement("db",
                           "namespace",
                           "people",
                           ListFields(List(Field("name", None))),
                           Some(Condition(LikeExpression("surname", "Poe"))),
                           None,
                           None,
                           Some(LimitOperator(1)))
    }
  }

  "Query with multiple filters of different types" should {
    "be correctly converted with equal operators" in {
      val filters = Seq(Filter("age", 1L, "="), Filter("height", 100L, "="))
      val originalStatement = SelectSQLStatement("db",
                                                 "namespace",
                                                 "people",
                                                 ListFields(List(Field("name", None))),
                                                 None,
                                                 None,
                                                 None,
                                                 Some(LimitOperator(1)))

      val enrichedStatement = originalStatement.addConditions(filters.map(Filter.unapply(_).get))

      enrichedStatement shouldEqual
        SelectSQLStatement(
          "db",
          "namespace",
          "people",
          ListFields(List(Field("name", None))),
          Some(Condition(
            TupledLogicalExpression(EqualityExpression("age", 1L), AndOperator, EqualityExpression("height", 100L)))),
          None,
          None,
          Some(LimitOperator(1))
        )
    }
    "be correctly converted with equal operators and existing Condition" in {
      val filters = Seq(Filter("age", 1L, "="), Filter("height", 100L, "="))
      val originalStatement = SelectSQLStatement("db",
                                                 "namespace",
                                                 "people",
                                                 ListFields(List(Field("name", None))),
                                                 Some(Condition(EqualityExpression("surname", "poe"))),
                                                 None,
                                                 None,
                                                 Some(LimitOperator(1)))

      val enrichedStatement = originalStatement.addConditions(filters.map(Filter.unapply(_).get))

      enrichedStatement shouldEqual
        SelectSQLStatement(
          "db",
          "namespace",
          "people",
          ListFields(List(Field("name", None))),
          Some(
            Condition(
              TupledLogicalExpression(
                EqualityExpression("surname", "poe"),
                AndOperator,
                TupledLogicalExpression(
                  EqualityExpression("age", 1L),
                  AndOperator,
                  EqualityExpression("height", 100L)
                )
              )
            )),
          None,
          None,
          Some(LimitOperator(1))
        )
    }
    "be correctly converted with different operators and existing Condition" in {
      val filters = Seq(Filter("age", 1L, ">"), Filter("height", 100L, "<="))
      val originalStatement = SelectSQLStatement("db",
                                                 "namespace",
                                                 "people",
                                                 ListFields(List(Field("name", None))),
                                                 Some(Condition(LikeExpression("surname", "poe"))),
                                                 None,
                                                 None,
                                                 Some(LimitOperator(1)))

      val enrichedStatement = originalStatement.addConditions(filters.map(Filter.unapply(_).get))

      enrichedStatement shouldEqual
        SelectSQLStatement(
          "db",
          "namespace",
          "people",
          ListFields(List(Field("name", None))),
          Some(
            Condition(
              TupledLogicalExpression(
                LikeExpression("surname", "poe"),
                AndOperator,
                TupledLogicalExpression(
                  ComparisonExpression("age", GreaterThanOperator, 1L),
                  AndOperator,
                  ComparisonExpression("height", LessOrEqualToOperator, 100L)
                )
              )
            )),
          None,
          None,
          Some(LimitOperator(1))
        )
    }
    "be correctly converted with different operators and existing Conditions" in {
      val filters = Seq(Filter("age", 1L, ">"), Filter("height", 100L, "<="))
      val originalStatement = SelectSQLStatement(
        "db",
        "namespace",
        "people",
        ListFields(List(Field("name", None))),
        Some(
          Condition(
            TupledLogicalExpression(LikeExpression("surname", "poe"), OrOperator, EqualityExpression("number", 1.0)))),
        None,
        None,
        Some(LimitOperator(1))
      )

      val enrichedStatement = originalStatement.addConditions(filters.map(Filter.unapply(_).get))

      enrichedStatement shouldEqual
        SelectSQLStatement(
          "db",
          "namespace",
          "people",
          ListFields(List(Field("name", None))),
          Some(
            Condition(
              TupledLogicalExpression(
                TupledLogicalExpression(LikeExpression("surname", "poe"),
                                        OrOperator,
                                        EqualityExpression("number", 1.0)),
                AndOperator,
                TupledLogicalExpression(
                  ComparisonExpression("age", GreaterThanOperator, 1L),
                  AndOperator,
                  ComparisonExpression("height", LessOrEqualToOperator, 100L)
                )
              )
            )),
          None,
          None,
          Some(LimitOperator(1))
        )
    }
  }

}
