package com.example

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class BlankFragment(private val plugin: ExamplePlugin) : BottomSheetDialogFragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(
            plugin.resources!!.getLayout(
                plugin.resources!!.getIdentifier(
                    "fragment_blank",
                    "layout",
                    BuildConfig.LIBRARY_PACKAGE_NAME
                )
            ),
            container,
            false
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val keyInput = view.findViewById<EditText>(
            plugin.resources!!.getIdentifier(
                "wyzieApiKey",
                "id",
                BuildConfig.LIBRARY_PACKAGE_NAME
            )
        )

        val saveButton = view.findViewById<Button>(
            plugin.resources!!.getIdentifier(
                "saveWyzieKey",
                "id",
                BuildConfig.LIBRARY_PACKAGE_NAME
            )
        )

        keyInput?.setText(plugin.getWyzieApiKey())

        saveButton?.setOnClickListener {
            val key = keyInput?.text?.toString()?.trim().orEmpty()

            plugin.setWyzieApiKey(key)

            Toast.makeText(
                requireContext(),
                if (key.isBlank()) "WyzieSubs key borrada" else "WyzieSubs key guardada",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}
