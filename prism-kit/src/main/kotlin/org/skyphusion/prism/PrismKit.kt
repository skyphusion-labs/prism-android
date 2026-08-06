package org.skyphusion.prism

/** Library identity (mirrors iOS PrismKit). */
object PrismKit {
  const val NAME: String = "PrismKit"
  const val VERSION: String = "0.3.2"

  fun health(): String = "ok:$NAME"
}
