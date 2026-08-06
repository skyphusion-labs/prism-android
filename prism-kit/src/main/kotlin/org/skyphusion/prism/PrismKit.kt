package org.skyphusion.prism

/** Library identity (mirrors iOS PrismKit). */
object PrismKit {
  const val NAME: String = "PrismKit"
  const val VERSION: String = "0.3.4"

  fun health(): String = "ok:$NAME"
}
