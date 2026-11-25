package com.navigationgps.desert.billing

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import com.navigationgps.desert.R
import java.util.Locale

class CountryCodeAdapter(
    context: Context,
    private val countryCodes: List<Pair<String, String>>
) : ArrayAdapter<Pair<String, String>>(context, 0, countryCodes) {

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        return createView(position, convertView, parent, isDropdown = false)
    }

    override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
        return createView(position, convertView, parent, isDropdown = true)
    }

    private fun createView(
        position: Int,
        convertView: View?,
        parent: ViewGroup,
        isDropdown: Boolean
    ): View {
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(R.layout.item_country_code, parent, false)

        val tvCountryCode = view.findViewById<TextView>(R.id.tvCountryCode)
        val (code, name) = countryCodes[position]

        if (isDropdown) {
            tvCountryCode.text = String.format(Locale.US, "%s  %s", code, name)
        } else {
            // Strip "+" prefix for closed spinner — show only digits (e.g., "962")
            tvCountryCode.text = String.format(Locale.US, "%s", code.removePrefix("+"))
        }
        // Force Latin numeral display (set AFTER text to prevent override)
        tvCountryCode.textLocale = Locale.US

        return view
    }

    fun getCodeForPosition(position: Int): String {
        return countryCodes[position].first
    }
}
