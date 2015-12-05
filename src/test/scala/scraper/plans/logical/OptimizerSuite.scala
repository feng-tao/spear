package scraper.plans.logical

import scala.language.implicitConversions

import org.scalacheck.Arbitrary
import org.scalacheck.Prop.forAll
import org.scalacheck.util.Pretty
import org.scalatest.prop.Checkers

import scraper.Test.defaultSettings
import scraper.expressions.Literal.True
import scraper.expressions.Predicate.splitConjunction
import scraper.expressions._
import scraper.generators.expressions._
import scraper.plans.Optimizer.{CNFConversion, ReduceFilters}
import scraper.trees.RulesExecutor.{EndCondition, FixedPoint}
import scraper.trees.{Rule, RulesExecutor}
import scraper.types.{TestUtils, TupleType}
import scraper.{Analyzer, LocalCatalog, LoggingFunSuite}

class OptimizerSuite extends LoggingFunSuite with Checkers with TestUtils {
  private implicit def prettyExpression(expression: Expression): Pretty = Pretty {
    _ => "\n" + expression.prettyTree
  }

  private def testRule(
    rule: Rule[LogicalPlan], endCondition: EndCondition
  )(
    f: (LogicalPlan => LogicalPlan) => Unit
  ): Unit = {
    test(rule.getClass.getSimpleName stripSuffix "$") {
      val analyzer = new Analyzer(new LocalCatalog)
      val optimizer = new RulesExecutor[LogicalPlan] {
        override def batches: Seq[RuleBatch] = Seq(
          RuleBatch("TestBatch", FixedPoint.Unlimited, rule :: Nil)
        )
      }

      f(analyzer andThen optimizer)
    }
  }

  testRule(CNFConversion, FixedPoint.Unlimited) { optimizer =>
    implicit val arbPredicate = Arbitrary(genPredicate(TupleType.empty.toAttributes))

    check(
      forAll { predicate: Expression =>
        val optimizedPlan = optimizer(SingleRowRelation filter predicate)
        val conditions = optimizedPlan.collect {
          case _ Filter condition => splitConjunction(condition)
        }.flatten

        conditions.forall {
          // Within generated predicate expressions, there can be nested conjunctions within
          // comparison expressions, which should be ignore.  For example, the following predicate
          // is in CNF although the `=` comparison contains a nested conjunction:
          //
          //   (a > 1) AND ((TRUE AND FALSE) = FALSE)
          //
          // Here we simply replace them with a boolean literal.
          _ transformDown {
            case BinaryComparison(_ And _, _) => True
            case BinaryComparison(_, _ And _) => True
          } forall {
            case _ And _ => false
            case _       => true
          }
        }
      },

      // CNF conversion may potentially expand the predicate significantly and slows down the test
      // quite a bit.  Here we restrict the max size of the expression tree to avoid slow test runs.
      MaxSize(20)
    )
  }

  testRule(ReduceFilters, FixedPoint.Unlimited) { optimizer =>
    implicit val arbPredicate = Arbitrary(genPredicate(TupleType.empty.toAttributes))

    check { (p1: Expression, p2: Expression) =>
      val optimized = optimizer(SingleRowRelation filter p1 filter p2)
      val conditions = optimized.collect {
        case f: Filter => f.condition
      }

      conditions == Seq(p1 && p2)
    }
  }
}
