package st.flrn.hermes.api.runtime

import kotlinx.serialization.Serializable

/** An omitted request field, explicit JSON null, or concrete value. */
public sealed interface Patch<out Value> {
    public data object Absent : Patch<Nothing>
    public data object Null : Patch<Nothing>
    public data class Value<Value>(public val value: Value) : Patch<Value>
}

/** The value of a closed empty JSON object. */
@Serializable
public class EmptyObject
