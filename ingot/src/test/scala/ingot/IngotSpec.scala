/**
 * Copyright 2018 Tamas Neltz
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ingot

import cats.Id
import org.scalatest._
import Matchers._
import cats.syntax.either._
import cats.~>

import scala.util.Try

final case class SimpleState(id: Int)

object SimpleState {
  val empty = SimpleState(0)
}

class IngotSpec extends FlatSpec {
  implicit val tryOptionGuard = new Guard[Try, Option] {
    override def apply[A](x: Try[A]): Option[Either[Throwable, A]] = x match {
      case scala.util.Success(x) => Some(Right(x))
      case scala.util.Failure(err) => Some(Left(err))
    }
  }

  "Brick" should "correctly compose" in {
    import cats.instances.option._
    (for {
      a <- Brick.guard[Option](Try("a"))
      b <- Brick.right[Throwable](Option("b"))
      c <- Brick.rightT[Option, Throwable]("c")
    } yield a + b + c).runA() should equal(Some(Right("abc")))
  }

  "Clay" should "correctly compose" in {
    (for {
      a <- Clay.rightT[Int]("a")
      b <- Clay.rightT[Int]("b")
    } yield a + b).runA() should equal(Right("ab"))
  }

  type SimpleTestResult = Ingot[Id, SimpleState, String, Int]
  "Ingot" should "correctly map" in {
    val res: SimpleTestResult = for {
      x <- Ingot.rightT(5)
      _ <- Ingot.log("This log message is ignored for now".asInfo)
      y <- Ingot.rightT(3)
    } yield x + y

    val result: Either[String, Int] = res.runA(SimpleState.empty)
    result should equal(Right(8))
  }

  it should "correctly collect logs" in {
    val res: SimpleTestResult = for {
      _ <- Ingot.log("Something".asInfo)
      _ <- Ingot.log("Something else".asInfo)
      _ <- Ingot.log(Vector("3rd log".asDebug, "4th log".asInfo))
    } yield 5

    val result: Logs = res.runL(SimpleState.empty)
    result should equal(Vector(LogMessage("Something", LogLevel.Info), LogMessage("Something else", LogLevel.Info), LogMessage("3rd log", LogLevel.Debug), LogMessage("4th log", LogLevel.Info)))
  }

  it should "correctly transform the state" in {
    val res: SimpleTestResult = for {
      _ <- Ingot.log[Id, SimpleState, String]("Starting off".asInfo)
      st <- Ingot.get
      _ <- Ingot.set(SimpleState(st.id * 2))
      _ <- Ingot.modify((x: SimpleState) => SimpleState(x.id + 3))
      st <- Ingot.get
    } yield st.id
    val result: SimpleState = res.runS(SimpleState(1))
    result should equal(SimpleState(5))
  }

  it should "correctly use inspects" in {
    val res: SimpleTestResult = for {
      _ <- Ingot.inspect[String]((st: SimpleState) => Either.right[String, String](""): Id[Either[String, String]])
      _ <- Ingot.inspectE((st: SimpleState) => st.id)
      _ <- Ingot.inspectF[Id, SimpleState, String, Int]((st: SimpleState) => Either.right[String, Int](st.id))
      _ <- Ingot.inspectL((st: SimpleState) => (Vector.empty[LogMessage], Either.right[String, String]("")))
      _ <- Ingot.inspectEL((st: SimpleState) => (Vector.empty[LogMessage], ""))
      _ <- Ingot.inspectFL[Id, SimpleState, String, String]((st: SimpleState) => (Vector.empty[LogMessage], Either.right[String, String]("")))
    } yield 5
    val result: Either[String, Int] = res.runA(SimpleState(1))
    result should equal(Either.right[String, Int](5))
  }

  trait DbConnection {
    def connNum: Int
  }

  trait HttpClient {
    def run: String => String
  }

  implicit val dbCompositeState =
    CompositeState.instance[DbConnection, (DbConnection, HttpClient)](_._1, { case (x, y) => (y, x._2) })

  implicit val httpCompositeState =
    CompositeState.instance[HttpClient, (DbConnection, HttpClient)](_._2, { case (x, y) => (x._1, y) })

  private def getIdFromDb(id: Int): Ingot[Id, DbConnection, String, String] = Ingot.pure(s"id:${id.toString}")

  private def getDataFromApi(url: String): Ingot[Id, HttpClient, String, String] = Ingot.pure(s"url:$url")

  private def getFromDbAndApi: Ingot[Id, (DbConnection, HttpClient), String, String] = {
    for {
      id <- getIdFromDb(5).transformS[(DbConnection, HttpClient)]
      url <- getDataFromApi("http://localhost").transformS[(DbConnection, HttpClient)]
    } yield s"$id$url"
  }

  it should "correctly combine multiple states" in {
    val st = (new DbConnection { override val connNum = 5 }, new HttpClient { override def run = identity })

    val result = getFromDbAndApi.runA(st)
    result should equal(Right("id:5url:http://localhost"))
  }

  it should "correctly transforms between monads" in {
    import cats.instances.all._

    implicit val idToList = new (List ~> Option) {
      def apply[A](x: List[A]): Option[A] = x.headOption
    }
    val lst = Ingot.right[Unit, String](List(1, 2, 3))
    val opt = lst.withMonad[Option]
    opt.runA() should equal(Some(Right(1)))
  }

  it should "use guards" in {
    val ex = new Exception("error")
    val failed: Try[String] = scala.util.Failure(ex)
    val result = Ingot.guard[Id, Unit](failed).runA()
    result should equal(Left(ex))
  }
}
