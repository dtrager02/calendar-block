package blocker

import java.time.Duration

object Strategies:

  /** Weighted score of candidate `c` inside nominal `window`; `occupied(email)` is that person's taken time. */
  def score(o: Occurrence, c: Interval, window: Interval, occupied: String => List[Interval]): Double =
    o.request.strategies.toList.map { (strategy, weight) =>
      weight * (strategy match
        case Strategy.PreferSoonest => preferSoonest(o.period, c)
        case Strategy.MinimalGaps   => mean(o.request.emails.toList.map(e => minimalGaps(window, c, occupied(e))))
      )
    }.sum

  def preferSoonest(period: Interval, c: Interval): Double =
    1 - ratio(Duration.between(period.start, c.start), period.length.minus(c.length))

  /** 1 when `c` sits flush against busy time or window edges on both sides, 0 when it leaves the most slack. */
  def minimalGaps(window: Interval, c: Interval, busy: List[Interval]): Double =
    val before = (window.start :: busy.collect { case b if !b.end.isAfter(c.start) => b.end }).max
    val after  = (window.end :: busy.collect { case b if !b.start.isBefore(c.end) => b.start }).min
    val gaps   = Duration.between(before, c.start).plus(Duration.between(c.end, after))
    1 - ratio(gaps, window.length.minus(c.length))

  private def ratio(a: Duration, b: Duration): Double =
    if b.isZero || b.isNegative then 0.0
    else math.min(1.0, math.max(0.0, a.toMillis.toDouble / b.toMillis))

  private def mean(xs: List[Double]): Double = if xs.isEmpty then 0.0 else xs.sum / xs.size
