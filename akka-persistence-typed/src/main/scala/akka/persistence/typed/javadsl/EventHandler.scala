/**
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.javadsl

import java.util.function.BiFunction

import akka.annotation.InternalApi
import akka.util.OptionVal

/**
 * FunctionalInterface for reacting on events having been persisted
 *
 * Used with [[EventHandlerBuilder]] to setup the behavior of a [[PersistentBehavior]]
 */
@FunctionalInterface
trait EventHandler[Event, State] {
  def apply(state: State, event: Event): State
}

object EventHandlerBuilder {
  def builder[Event, State >: Null](): EventHandlerBuilder[Event, State] =
    new EventHandlerBuilder[Event, State]()

  /**
   * INTERNAL API
   */
  @InternalApi private final case class EventHandlerCase[Event, State](
    eventPredicate: Event ⇒ Boolean,
    statePredicate: State ⇒ Boolean,
    handler:        BiFunction[State, Event, State])
}

final class EventHandlerBuilder[Event, State >: Null]() {
  import EventHandlerBuilder.EventHandlerCase

  private var cases: List[EventHandlerCase[Event, State]] = Nil

  private def addCase(predicate: Event ⇒ Boolean, handler: BiFunction[State, Event, State]): Unit = {
    cases = EventHandlerCase[Event, State](predicate, _ ⇒ true, handler) :: cases
  }

  /**
   * Match any event which is an instance of `E` or a subtype of `E`
   */
  def matchEvent[E <: Event](eventClass: Class[E], biFunction: BiFunction[State, E, State]): EventHandlerBuilder[Event, State] = {
    addCase(e ⇒ eventClass.isAssignableFrom(e.getClass), biFunction.asInstanceOf[BiFunction[State, Event, State]])
    this
  }

  def matchEvent[E <: Event, S <: State](eventClass: Class[E], stateClass: Class[S],
                                         biFunction: BiFunction[S, E, State]): EventHandlerBuilder[Event, State] = {

    cases = EventHandlerCase[Event, State](
      eventPredicate = e ⇒ eventClass.isAssignableFrom(e.getClass),
      statePredicate = s ⇒ stateClass.isAssignableFrom(s.getClass),
      biFunction.asInstanceOf[BiFunction[State, Event, State]]) :: cases
    this
  }

  /**
   * Match any event
   *
   * Builds and returns the handler since this will not let through any states to subsequent match statements
   */
  def matchAny(biFunction: BiFunction[State, Event, State]): EventHandler[Event, State] = {
    addCase(_ ⇒ true, biFunction.asInstanceOf[BiFunction[State, Event, State]])
    build()
  }

  /**
   * Compose this builder with another builder. The handlers in this builder will be tried first followed
   * by the handlers in `other`.
   */
  def orElse(other: EventHandlerBuilder[Event, State]): EventHandlerBuilder[Event, State] = {
    val newBuilder = new EventHandlerBuilder[Event, State]
    // problem with overloaded constructor with `cases` as parameter
    newBuilder.cases = other.cases ::: cases
    newBuilder
  }

  /**
   * Builds and returns a handler from the appended states. The returned [[EventHandler]] will throw a [[scala.MatchError]]
   * if applied to an event that has no defined case.
   *
   * The builder is reset to empty after build has been called.
   */
  def build(): EventHandler[Event, State] = {
    val builtCases = cases.reverse.toArray

    new EventHandler[Event, State] {
      def apply(state: State, event: Event): State = {
        var result: OptionVal[State] = OptionVal.None
        var idx = 0
        while (idx < builtCases.length && result.isEmpty) {
          val curr = builtCases(idx)
          if (curr.statePredicate(state) && curr.eventPredicate(event)) {
            result = OptionVal.Some[State](curr.handler.apply(state, event))
          }
          idx += 1
        }

        result match {
          case OptionVal.None    ⇒ throw new MatchError(s"No match found for event [${event.getClass}] and state [${state.getClass}]. Has this event been stored using an EventAdapter?")
          case OptionVal.Some(s) ⇒ s
        }
      }
    }
  }
}
