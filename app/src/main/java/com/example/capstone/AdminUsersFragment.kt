// AdminUsersFragment.kt
package com.example.capstone

import android.app.AlertDialog
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.firebase.auth.ktx.auth
import com.google.firebase.functions.ktx.functions
import com.google.firebase.ktx.Firebase
import com.smartwaste.capstone.R
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class AdminUsersFragment : Fragment(R.layout.fragment_admin_users_pretty) {

    private val functions by lazy(LazyThreadSafetyMode.NONE) { Firebase.functions("us-central1") }

    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var rv: RecyclerView
    private lateinit var tvTotal: TextView
    private lateinit var tvVerified: TextView
    private lateinit var tvUnverified: TextView
    private var etSearch: EditText? = null
    private var ivFilter: ImageView? = null
    private var loadJob: kotlinx.coroutines.Job? = null
    private lateinit var adapter: AdminUsersAdapter
    private var searchJob: kotlinx.coroutines.Job? = null

    // UI state (single source of truth)
    private var currentQuery: String = ""
    private var currentFilter: String = "all" // "all" | "verified" | "unverified"

    private var nextPageToken: String? = null


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        swipe = view.findViewById(R.id.swipe)
        rv = view.findViewById(R.id.rvUsers)
        tvTotal = view.findViewById(R.id.tvTotal)
        tvVerified = view.findViewById(R.id.tvVerified)
        tvUnverified = view.findViewById(R.id.tvUnverified)
        etSearch = view.findViewById(R.id.etSearch)
        ivFilter = view.findViewById(R.id.ivFilter)

        rv.layoutManager = LinearLayoutManager(requireContext())
        adapter = AdminUsersAdapter(
            onToggle = { user -> toggleDisabled(user) },
            onDelete = { user -> deleteUser(user) }
        )
        rv.adapter = adapter

        swipe.setOnRefreshListener {
            clearSearchFocus()
            resetFiltersToAll()
            loadUsers(reset = true)
        }


        swipe.setOnChildScrollUpCallback { _, _ -> rv.canScrollVertically(-1) }



        view.findViewById<FloatingActionButton>(R.id.fabAddUser)?.setOnClickListener {
            CreateUserDialog.newInstance().show(parentFragmentManager, "CreateUserDialog")
        }

        parentFragmentManager.setFragmentResultListener("CreateUserDialog", viewLifecycleOwner) { _, b ->
            if (b.getBoolean("created", false)) loadUsers(reset = true)
        }

//        rv.setOnTouchListener { _, _ -> clearSearchFocus(); false }
//        swipe.setOnTouchListener { _, _ -> clearSearchFocus(); false }

// when user scrolls the list
        rv.setOnScrollChangeListener { _, _, _, _, _ ->
            if (etSearch?.hasFocus() != true) clearSearchFocus()
        }


// on actionSearch from keyboard
        etSearch?.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                clearSearchFocus(); true
            } else false
        }

// ensure a focusable root so taps outside can take focus
//        (view as? ViewGroup)?.isFocusableInTouchMode = true
//        view.setOnClickListener { clearSearchFocus() }
        // Filter popup
        ivFilter?.setOnClickListener { anchor ->

            clearSearchFocus()
            (view as? ViewGroup)?.requestFocus()

            val pm = android.widget.PopupMenu(requireContext(), anchor)
            pm.menu.add(0, 1, 0, "All")
            pm.menu.add(0, 2, 1, "Verified")
            pm.menu.add(0, 3, 2, "Unverified")
            pm.setOnMenuItemClickListener { item ->
                currentFilter = when (item.itemId) {
                    2 -> "verified"
                    3 -> "unverified"
                    else -> "all"
                }

                currentQuery = ""
                etSearch?.setText("")
                nextPageToken = null
                loadUsers(reset = true)
                true
            }
            pm.show()
        }


        // Search
        etSearch?.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {}
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                currentQuery = (s?.toString() ?: "").trim()
                searchJob?.cancel()
                searchJob = viewLifecycleOwner.lifecycleScope.launch {
                    kotlinx.coroutines.delay(300) // wait 300ms after last keystroke
                    loadUsers(reset = true)
                }
            }
        })

        val root = view.findViewById<View>(R.id.root)
        root.setOnTouchListener { _, ev ->
            if (ev.action == android.view.MotionEvent.ACTION_DOWN) {
                val r = android.graphics.Rect()
                etSearch?.getGlobalVisibleRect(r)
                if (!r.contains(ev.rawX.toInt(), ev.rawY.toInt())) {
                    // move focus away so the caret can disappear
                    rv.requestFocus()
                    clearSearchFocus()
                    etSearch?.setCursorVisible(false)
                }
            }
            false
        }



        val til = view.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.tilSearch)

        etSearch?.setOnFocusChangeListener { _, hasFocus ->
            etSearch?.setCursorVisible(hasFocus)
            swipe.isEnabled = !hasFocus
            til?.isHintEnabled = hasFocus || (etSearch?.text?.isNotEmpty() == true)
        }


        etSearch?.setOnTouchListener { v, ev ->
            if (ev.action == android.view.MotionEvent.ACTION_DOWN) {
                v.parent?.requestDisallowInterceptTouchEvent(true)
            }
            false
        }



        etSearch?.apply {
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
        }



        swipe.isRefreshing = true
        loadUsers(reset = true)
    }

    private fun View.hideKeyboard() {
        val imm = context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(windowToken, 0)
    }

    private fun clearSearchFocus() {
        etSearch?.clearFocus()
        view?.hideKeyboard()
    }

    private fun resetFiltersToAll() {
        // Reset filter + search + paging
        currentFilter = "all"
        currentQuery = ""
        etSearch?.setText("")
        nextPageToken = null
    }


    private suspend fun adminCall(name: String, data: Map<String, Any?> = emptyMap()): Map<*, *> {
        val idToken = Firebase.auth.currentUser?.getIdToken(true)?.await()?.token
            ?: throw IllegalStateException("Not signed in")
        val res = functions.getHttpsCallable(name).call(data + mapOf("idToken" to idToken)).await()
        @Suppress("UNCHECKED_CAST")
        return (res.data as? Map<*, *>) ?: emptyMap<String, Any?>()
    }


    private fun loadUsers(reset: Boolean = false) {
        // cancel any previous run
        loadJob?.cancel()

        // create the job first
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Turn on the spinner *inside* the coroutine
                swipe.isRefreshing = true

                val (allUsers, next) = fetchUsers(pageToken = if (reset) null else nextPageToken)
                nextPageToken = next
                updateOverviewCounters(allUsers)
                adapter.submitList(allUsers)
            } catch (e: Exception) {
                // Cancellation is normal during rapid searches/navigation.
                if (e !is kotlinx.coroutines.CancellationException) {
                    Toast.makeText(requireContext(), e.message ?: "Failed to load", Toast.LENGTH_LONG).show()
                }
            } finally {
                // This runs when the coroutine actually ran; turn spinner off
                if (view != null) swipe.isRefreshing = false
            }
        }.also { job ->
            // IMPORTANT: This runs even if the job is canceled before the block executes
            job.invokeOnCompletion {
                android.util.Log.d("AdminUsers", "loadUsers job completed (cause=${it?.javaClass?.simpleName})")
                view?.post { swipe.isRefreshing = false }
            }

        }
    }



    private suspend fun fetchUsers(pageToken: String? = null): Pair<List<AdminUser>, String?> {
        val verifiedParam: Any = when (currentFilter) {
            "verified"   -> true
            "unverified" -> false
            else         -> "all"
        }

        val res = adminCall(
            "admin_listUsersPaged",
            mapOf(
                "pageSize" to 1000,
                "pageToken" to pageToken,
                "verified"  to verifiedParam,  // <-- boolean or "all"
                "query"     to currentQuery
            )
        )

        val dbg = res["debug"] as? Map<*, *>
        android.util.Log.d(
            "AdminUsers",
            "filter=$currentFilter q='$currentQuery' serverDebug=${dbg ?: "null"} usersSize="+((res["users"] as? List<*>)?.size ?: -1)
        )

        val users = (res["users"] as? List<*>)?.map { any ->
            val m = any as Map<*, *>
            AdminUser(
                uid            = m["uid"] as? String ?: "",
                email          = m["email"] as? String ?: "",
                isAdmin        = (m["isAdmin"] as? Boolean) == true,
                emailVerified  = (m["emailVerified"] as? Boolean) == true,
                disabled       = (m["disabled"] as? Boolean) == true,
                creationTime   = m["creationTime"] as? String,
                lastSignInTime = m["lastSignInTime"] as? String
            )
        } ?: emptyList()

        val next = res["nextPageToken"] as? String
        return users to next
    }


    private fun toggleDisabled(u: AdminUser) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                adminCall("admin_toggleDisabled", mapOf(
                    "uid" to u.uid,
                    "disabled" to !u.disabled
                ))
                loadUsers(reset = true)
                Toast.makeText(requireContext(), if (!u.disabled) "User disabled" else "User enabled", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), e.message ?: "Failed to toggle", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun deleteUser(u: AdminUser) {
        AlertDialog.Builder(requireContext())
            .setTitle("Delete user?")
            .setMessage("This will permanently remove ${u.email}.")
            .setPositiveButton("Delete") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        adminCall("admin_deleteUser", mapOf("uid" to u.uid))
                        loadUsers(reset = true)
                        Toast.makeText(requireContext(), "User deleted", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(requireContext(), e.message ?: "Failed to delete", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateOverviewCounters(list: List<AdminUser>) {
        val total = list.size
        val verified = list.count { it.emailVerified }
        val unverified = total - verified
        tvTotal.text = total.toString()
        tvVerified.text = verified.toString()
        tvUnverified.text = unverified.toString()
    }

    override fun onPause() {
        super.onPause()
        clearSearchFocus()
        etSearch?.setCursorVisible(false)
    }


    override fun onResume() {
        super.onResume()
        swipe.isRefreshing = false
        swipe.isEnabled = etSearch?.hasFocus() != true
    }

    override fun onStop() {
        super.onStop()
        swipe.isRefreshing = false
    }

    override fun onDestroyView() {
        searchJob?.cancel()
        loadJob?.cancel()
        swipe.isRefreshing = false
        swipe.isEnabled = true
        super.onDestroyView()
    }


}
