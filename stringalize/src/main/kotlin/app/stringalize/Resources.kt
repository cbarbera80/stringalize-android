package app.stringalize

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView

/** Finds the released text for a resource id, using [resources] to learn its name. */
internal object Lookup {
    fun text(resources: Resources, id: Int): String? {
        val engine = Stringalize.engine ?: return null
        if (engine.activeRelease == null) return null
        val name = name(resources, id, "string") ?: return null
        return engine.text(name, resources.configuration.locales.toList())
    }

    fun quantityText(resources: Resources, id: Int, quantity: Int): String? {
        val engine = Stringalize.engine ?: return null
        if (engine.activeRelease == null) return null
        val name = name(resources, id, "plurals") ?: return null
        return engine.quantityText(name, quantity, resources.configuration.locales.toList())
    }

    private fun name(resources: Resources, id: Int, type: String): String? = try {
        if (resources.getResourceTypeName(id) == type) resources.getResourceEntryName(id) else null
    } catch (e: Resources.NotFoundException) {
        null
    }
}

/**
 * Resources that answer string lookups from the active release and everything else from the
 * app's own resources. getString, getQuantityString and their formatting variants all go
 * through the two methods overridden here.
 */
@Suppress("DEPRECATION")
internal class StringalizeResources(val base: Resources) : Resources(base.assets, base.displayMetrics, base.configuration) {
    override fun getText(id: Int): CharSequence = Lookup.text(base, id) ?: base.getText(id)

    override fun getText(id: Int, def: CharSequence?): CharSequence? = Lookup.text(base, id) ?: base.getText(id, def)

    override fun getQuantityText(id: Int, quantity: Int): CharSequence = Lookup.quantityText(base, id, quantity) ?: base.getQuantityText(id, quantity)
}

internal class StringalizeContext(base: Context) : ContextWrapper(base) {
    private var resources: StringalizeResources? = null
    private var configuration: Configuration? = null
    private var inflater: LayoutInflater? = null

    // Rebuilt when the base resources change, for instance when the language or the
    // night mode changes while an application context is wrapped.
    override fun getResources(): Resources {
        val base = super.getResources()
        val current = resources
        if (current != null && current.base === base && configuration == base.configuration) return current
        return StringalizeResources(base).also {
            resources = it
            configuration = Configuration(base.configuration)
        }
    }

    override fun getSystemService(name: String): Any? {
        if (name != Context.LAYOUT_INFLATER_SERVICE) return super.getSystemService(name)
        return inflater ?: StringalizeInflater(super.getSystemService(name) as LayoutInflater, this).also { inflater = it }
    }

    // AppCompat applies night mode and per-app languages through a configuration context.
    override fun createConfigurationContext(overrideConfiguration: Configuration): Context =
        StringalizeContext(super.createConfigurationContext(overrideConfiguration))
}

/**
 * Text set in layouts (`android:text="@string/…"`) is read by the view itself, not through
 * getText, so the released text is applied after each TextView is created.
 *
 * Views come from the factory AppCompat or Material installs, which this inflater wraps,
 * or from the platform widgets created below. Views declared by their full class name in an
 * app without such a factory keep their bundled text.
 */
internal class StringalizeInflater(original: LayoutInflater, context: Context) : LayoutInflater(original, context) {
    init {
        // A clone copies the factories of the inflater it came from without wrapping them.
        factory2?.let { if (it !is WrappingFactory) setFactory2(WrappingFactory(it)) }
            ?: factory?.let { if (it !is WrappingFactory) setFactory2(WrappingFactory(FactoryAdapter(it))) }
    }

    override fun cloneInContext(newContext: Context): LayoutInflater = StringalizeInflater(this, newContext)

    override fun setFactory(factory: Factory) {
        super.setFactory2(WrappingFactory(FactoryAdapter(factory)))
    }

    override fun setFactory2(factory: Factory2) {
        super.setFactory2(if (factory is WrappingFactory) factory else WrappingFactory(factory))
    }

    override fun onCreateView(name: String, attrs: AttributeSet): View? {
        for (prefix in PREFIXES) {
            try {
                createView(name, prefix, attrs)?.let { return applied(it, attrs) }
            } catch (e: ClassNotFoundException) {
                // Try the next package.
            }
        }
        return super.onCreateView(name, attrs)?.let { applied(it, attrs) }
    }

    private class WrappingFactory(private val delegate: Factory2) : Factory2 {
        override fun onCreateView(parent: View?, name: String, context: Context, attrs: AttributeSet): View? =
            delegate.onCreateView(parent, name, context, attrs)?.let { applied(it, attrs) }

        override fun onCreateView(name: String, context: Context, attrs: AttributeSet): View? =
            delegate.onCreateView(name, context, attrs)?.let { applied(it, attrs) }
    }

    private class FactoryAdapter(private val factory: Factory) : Factory2 {
        override fun onCreateView(parent: View?, name: String, context: Context, attrs: AttributeSet): View? = factory.onCreateView(name, context, attrs)

        override fun onCreateView(name: String, context: Context, attrs: AttributeSet): View? = factory.onCreateView(name, context, attrs)
    }

    companion object {
        // The packages the platform's own inflater looks in for unqualified tags.
        private val PREFIXES = arrayOf("android.widget.", "android.webkit.", "android.app.")
        private const val ANDROID = "http://schemas.android.com/apk/res/android"

        fun applied(view: View, attrs: AttributeSet): View {
            if (view !is TextView) return view
            val text = attrs.getAttributeResourceValue(ANDROID, "text", 0)
            if (text != 0) Lookup.text(view.resources, text)?.let { view.text = it }
            val hint = attrs.getAttributeResourceValue(ANDROID, "hint", 0)
            if (hint != 0) Lookup.text(view.resources, hint)?.let { view.hint = it }
            return view
        }
    }
}
