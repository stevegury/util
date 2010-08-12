package com.twitter.util

import com.twitter.util.TimeConversions._
import scala.collection.mutable.ArrayBuffer

object Future {
  def constant[A](a: A) = new Future[A] {
    def respond(k: A => Unit) { k(a) }
  }
}

abstract class Future[+A] extends (() => A) {
  private var cachedResult: Option[Any] = None

  def respond(k: A => Unit)

  def apply: A = apply(Math.MAX_LONG.millis).get

  def apply(timeout: Duration): Option[A] = cachedResult.synchronized {
    cachedResult.map(_.asInstanceOf[A]) orElse {
      val latch = new CountDownLatch(1)
      respond { a =>
        cachedResult = Some(a)
        latch.countDown()
      }
      latch.await(timeout)
      cachedResult.map(_.asInstanceOf[A])
    }
  }

  def getWithin(timeout: Duration): A = {
    apply(timeout).getOrElse(throw new TimeoutException(timeout.toString))
  }

  def foreach(k: A => Unit) { respond(k) }

  def map[B](f: A => B) = new Future[B] {
    def respond(k: B => Unit) {
      Future.this.respond(x => k(f(x)))
    }
  }

  def flatMap[B](f: A => Future[B]) = new Future[B] {
    def respond(k: B => Unit) {
      Future.this.respond(x => f(x).respond(k))
    }
  }

  def filter(p: A => Boolean) = new Future[A] {
    def respond(k: A => Unit) {
      Future.this.respond(x => if (p(x)) k(x) else ())
    }
  }
}

object NotifyingFuture {
  class ImmutableResult(message: String) extends Exception(message)
}

class NotifyingFuture[A] extends Future[A] {
  import NotifyingFuture._

  private var result: Option[A] = None
  private val computations = new ArrayBuffer[A => Unit]

  def setResult(result: A) {
    setResultIfEmpty(result) || {
      throw new ImmutableResult("Result set multiple times: " + result)
    }
  }

  def setResultIfEmpty(result: A) = {
    if (this.result.isDefined) false
    else {
      // don't want to execute callbacks in synchronized block
      val res = synchronized {
        // need to check again to avoid race-condition
        if (this.result.isDefined) false
        else {
          this.result = Some(result)
          true
        }
      }
      if (res) computations foreach(_(result))
      res
    }
  }

  def respond(k: A => Unit) {
    if (result.isDefined) k(result.get)
    else {
      // don't want to execute callbacks in synchronized block
      val isDefined = synchronized {
        result.isDefined || { computations += k; false }
      }
      if (isDefined) k(result.get)
    }
  }
}
