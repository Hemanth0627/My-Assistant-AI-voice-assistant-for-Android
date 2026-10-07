package com.hemanth.myassistant.actions

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import android.util.Log
import com.hemanth.myassistant.model.MessageApp
import java.util.Locale

private const val TAG = "AssistantActions"

data class Contact(val name: String, val number: String)

sealed interface ContactLookup {
    data class Found(val contact: Contact) : ContactLookup
    data class Ambiguous(val names: List<String>) : ContactLookup
    data object NotFound : ContactLookup
}

class CommunicationManager(context: Context) {

    private val appContext = context.applicationContext

    // How family members are often saved (English, Tamil, Hindi).
    private val relationGroups = listOf(
        listOf("dad", "appa", "father", "papa", "daddy", "pappa"),
        listOf("mom", "mum", "amma", "mother", "mummy", "maa")
    )

    private data class PhoneEntry(val number: String, val isPrimary: Boolean, val isMobile: Boolean)
    private data class Candidate(val name: String, val numbers: List<PhoneEntry>) {
        fun toContact(): Contact {
            val best = numbers.firstOrNull { it.isPrimary }
                ?: numbers.firstOrNull { it.isMobile }
                ?: numbers.first()
            return Contact(name, best.number)
        }
    }

    /** If the user said a phone number instead of a name, use it directly (no contacts needed). */
    fun asPhoneNumber(text: String): Contact? {
        val trimmed = text.trim()
        return if (trimmed.matches(Regex("^\\+?[0-9 ()-]{6,20}$"))) Contact(trimmed, trimmed) else null
    }

    /** Needs READ_CONTACTS permission. */
    fun findContact(spokenName: String): ContactLookup {
        val name = spokenName.trim()
            .replace(Regex("[^\\p{L}\\p{N} .'-]"), "") // letters, digits, space . ' -
            .take(60)
        if (name.isEmpty()) return ContactLookup.NotFound

        val candidates = search(name)

        // 1. Exact name ("Dad" == "Dad")
        val exact = candidates.filter { it.name.equals(name, ignoreCase = true) }
        if (exact.isNotEmpty()) return choose(exact)

        // 2. Family words ("Dad" may be saved as "Appa")
        relationGroups.firstOrNull { name.lowercase() in it }?.forEach { alternative ->
            val matches = search(alternative).filter { it.name.equals(alternative, ignoreCase = true) }
            if (matches.isNotEmpty()) return choose(matches)
        }

        // 3. Partial ("Kailash" in "Kailash Krishnanand")
        if (candidates.isNotEmpty()) return choose(candidates)

        return ContactLookup.NotFound
    }

    fun placeCall(contact: Contact, directCall: Boolean): ActionResult {
        val uri = Uri.fromParts("tel", contact.number, null)
        return if (directCall) {
            appContext.startSafely(
                Intent(Intent.ACTION_CALL, uri),
                successMessage = "Calling ${contact.name}.",
                failureMessage = "I couldn't start the call."
            )
        } else {
            appContext.startSafely(
                Intent(Intent.ACTION_DIAL, uri),
                successMessage = "I've opened the dialer for ${contact.name}. Tap the call button to connect.",
                failureMessage = "I couldn't open the dialer."
            )
        }
    }

    /** Opens the messaging app with the text ready. The USER presses Send. */
    fun openMessage(contact: Contact, text: String, app: MessageApp): ActionResult = when (app) {
        MessageApp.SMS -> appContext.startSafely(
            Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", contact.number, null))
                .putExtra("sms_body", text),
            successMessage = "Your message to ${contact.name} is ready. Tap send in Messages.",
            failureMessage = "I couldn't open your messaging app."
        )
        MessageApp.WHATSAPP -> {
            val phone = toInternationalDigits(contact.number)
            val uri = Uri.parse("https://wa.me/$phone?text=${Uri.encode(text)}")
            appContext.startSafely(
                Intent(Intent.ACTION_VIEW, uri).setPackage("com.whatsapp"),
                successMessage = "Your WhatsApp message to ${contact.name} is ready. Tap send.",
                failureMessage = "WhatsApp isn't installed, or I couldn't open it."
            )
        }
    }

    private fun choose(matches: List<Candidate>): ContactLookup =
        if (matches.size == 1) ContactLookup.Found(matches.first().toContact())
        else ContactLookup.Ambiguous(matches.map { it.name }.distinct().take(3))

    private fun search(name: String): List<Candidate> {
        val projection = arrayOf(
            Phone.CONTACT_ID, Phone.DISPLAY_NAME, Phone.NUMBER, Phone.IS_SUPER_PRIMARY, Phone.TYPE
        )
        val names = linkedMapOf<Long, String>()
        val numbers = mutableMapOf<Long, MutableList<PhoneEntry>>()

        try {
            appContext.contentResolver.query(
                Phone.CONTENT_URI,
                projection,
                "${Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$name%"),
                null
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(Phone.CONTACT_ID)
                val nameCol = cursor.getColumnIndexOrThrow(Phone.DISPLAY_NAME)
                val numberCol = cursor.getColumnIndexOrThrow(Phone.NUMBER)
                val primaryCol = cursor.getColumnIndexOrThrow(Phone.IS_SUPER_PRIMARY)
                val typeCol = cursor.getColumnIndexOrThrow(Phone.TYPE)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val displayName = cursor.getString(nameCol) ?: continue
                    val number = cursor.getString(numberCol) ?: continue
                    names[id] = displayName
                    numbers.getOrPut(id) { mutableListOf() } += PhoneEntry(
                        number = number,
                        isPrimary = cursor.getInt(primaryCol) != 0,
                        isMobile = cursor.getInt(typeCol) == Phone.TYPE_MOBILE
                    )
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Contacts permission missing", e)
            return emptyList()
        }

        return names.map { (id, displayName) -> Candidate(displayName, numbers.getValue(id)) }
    }

    /** "98765 43210" → "919876543210" (needed for WhatsApp links). */
    private fun toInternationalDigits(number: String): String {
        val country = appContext.getSystemService(TelephonyManager::class.java)
            ?.simCountryIso?.uppercase()?.takeIf { it.isNotEmpty() }
            ?: Locale.getDefault().country
        val e164 = PhoneNumberUtils.formatNumberToE164(number, country)
        return (e164 ?: number).filter { it.isDigit() }
    }
}