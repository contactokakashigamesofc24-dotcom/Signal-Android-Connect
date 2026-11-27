package org.thoughtcrime.securesms.components.settings.app.internal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.setFragmentResultListener
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.signal.appsettings.routes.AppSettingsNavigationEvent
import org.signal.appsettings.routes.AppSettingsRoute
import org.signal.appsettings.routes.navigateBack
import org.signal.appsettings.routes.navigateTo
import org.signal.core.ui.BottomSheetUtil
import org.signal.core.ui.permissions.PermissionDeniedBottomSheet
import org.signal.core.ui.permissions.RationaleDialog
import org.signal.core.util.AppUtil
import org.signal.core.util.Util
import org.signal.core.util.concurrent.SignalExecutors
import org.signal.core.util.concurrent.SimpleTask
import org.signal.core.util.logging.Log
import org.signal.core.util.readToList
import org.signal.core.util.requireLong
import org.signal.core.util.requireString
import org.signal.ringrtc.CallManager
import org.signal.storageservice.protos.calls.quality.SubmitCallQualitySurveyRequest
import org.thoughtcrime.securesms.BuildConfig
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.calls.quality.CallQualityBottomSheetFragment
import org.thoughtcrime.securesms.components.settings.DSLConfiguration
import org.thoughtcrime.securesms.components.settings.DSLSettingsFragment
import org.thoughtcrime.securesms.components.settings.DSLSettingsText
import org.thoughtcrime.securesms.components.settings.app.appSettingsRouter
import org.thoughtcrime.securesms.components.settings.app.privacy.advanced.AdvancedPrivacySettingsRepository
import org.thoughtcrime.securesms.components.settings.app.subscription.InAppPaymentsRepository
import org.thoughtcrime.securesms.components.settings.configure
import org.thoughtcrime.securesms.components.snackbars.SnackbarState
import org.thoughtcrime.securesms.components.snackbars.makeSnackbar
import org.thoughtcrime.securesms.conversation.ConversationIntents
import org.thoughtcrime.securesms.database.JobDatabase
import org.thoughtcrime.securesms.database.LocalMetricsDatabase
import org.thoughtcrime.securesms.database.LogDatabase
import org.thoughtcrime.securesms.database.MegaphoneDatabase
import org.thoughtcrime.securesms.database.OneTimePreKeyTable
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.model.InAppPaymentSubscriberRecord
import org.thoughtcrime.securesms.database.model.MessageRecord
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.jobmanager.JobTracker
import org.thoughtcrime.securesms.jobs.BackfillCollapsedMessageJob
import org.thoughtcrime.securesms.jobs.CheckKeyTransparencyJob
import org.thoughtcrime.securesms.jobs.DownloadLatestEmojiDataJob
import org.thoughtcrime.securesms.jobs.EmojiSearchIndexDownloadJob
import org.thoughtcrime.securesms.jobs.InAppPaymentKeepAliveJob
import org.thoughtcrime.securesms.jobs.PreKeysSyncJob
import org.thoughtcrime.securesms.jobs.RefreshAttributesJob
import org.thoughtcrime.securesms.jobs.RefreshOwnProfileJob
import org.thoughtcrime.securesms.jobs.RemoteConfigRefreshJob
import org.thoughtcrime.securesms.jobs.RetrieveRemoteAnnouncementsJob
import org.thoughtcrime.securesms.jobs.RotateProfileKeyJob
import org.thoughtcrime.securesms.jobs.StorageForcePushJob
import org.thoughtcrime.securesms.jobs.UnreadReminderJob
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.megaphone.MegaphoneRepository
import org.thoughtcrime.securesms.megaphone.Megaphones
import org.thoughtcrime.securesms.payments.DataExportUtil
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.registration.data.QuickstartCredentialExporter
import org.thoughtcrime.securesms.ringrtc.CameraFpsRanges
import org.thoughtcrime.securesms.storage.StorageSyncHelper
import org.thoughtcrime.securesms.util.ConversationUtil
import org.thoughtcrime.securesms.util.adapter.mapping.MappingAdapter
import org.thoughtcrime.securesms.util.navigation.safeNavigate
import org.thoughtcrime.securesms.util.setIncognitoKeyboardEnabled
import org.whispersystems.signalservice.api.push.UsernameLinkComponents
import java.util.Optional
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

class InternalSettingsFragment : DSLSettingsFragment(R.string.preferences__internal_preferences, R.menu.internal_settings) {

  companion object {
    private val TAG = Log.tag(InternalSettingsFragment::class.java)
  }

  private val appSettingsRouter by appSettingsRouter(onNavigateToScreen = ::navigateToScreen)
  private val parentEventEmitter: (AppSettingsNavigationEvent) -> Unit = { appSettingsRouter.onEvent(it) }

  private lateinit var viewModel: InternalSettingsViewModel
  private var searchMenuItem: MenuItem? = null

  private val cameraFpsRangeDescription: String? by lazy { CameraFpsRanges.captureCameraRangesDescription(requireContext()) }

  private var scrollToPosition: Int = 0
  private val layoutManager: LinearLayoutManager?
    get() = recyclerView?.layoutManager as? LinearLayoutManager

  override fun onPause() {
    super.onPause()
    val firstVisiblePosition: Int? = layoutManager?.findFirstVisibleItemPosition()
    if (firstVisiblePosition != null) {
      SignalStore.internal.lastScrollPosition = firstVisiblePosition
    }
  }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)
    scrollToPosition = SignalStore.internal.lastScrollPosition
    initializeSearch(view)

    setFragmentResultListener(CallQualityBottomSheetFragment.REQUEST_KEY) { _, bundle ->
      if (bundle.getBoolean(CallQualityBottomSheetFragment.REQUEST_KEY, false)) {
        makeSnackbar(
          SnackbarState(
            message = getString(R.string.CallQualitySheet__thanks_for_your_feedback)
          )
        )
      }
    }
  }

  override fun bindAdapter(adapter: MappingAdapter) {
    val repository = InternalSettingsRepository(requireContext())
    val factory = InternalSettingsViewModel.Factory(repository)
    viewModel = ViewModelProvider(this, factory)[InternalSettingsViewModel::class.java]

    viewModel.state.observe(viewLifecycleOwner) {
      val mappingModelList = getConfiguration(it).toMappingModelList()
      val filteredList = viewModel.filterPreferences(requireContext(), mappingModelList, it.searchQuery)

      adapter.submitList(filteredList) {
        if (scrollToPosition != 0 && it.searchQuery.isBlank()) {
          layoutManager?.scrollToPositionWithOffset(scrollToPosition, 0)
          scrollToPosition = 0
        }
      }
    }
  }

  override fun onToolbarNavigationClicked() {
    if (searchMenuItem?.isActionViewExpanded == true) {
      searchMenuItem?.collapseActionView()
    } else {
      parentEventEmitter.navigateBack()
    }
  }

  private fun navigateToScreen(route: AppSettingsRoute) {
    val directions = when (route) {
      AppSettingsRoute.InternalRoute.RemoteConfig -> InternalSettingsFragmentDirections.actionInternalSettingsFragmentToInternalRemoteConfigFragment()
      AppSettingsRoute.InternalRoute.Issues -> InternalSettingsFragmentDirections.actionInternalSettingsFragmentToInternalIssuesFragment()
      AppSettingsRoute.InternalRoute.SqlitePlayground -> InternalSettingsFragmentDirections.actionInternalSettingsFragmentToInternalSqlitePlaygroundFragment()
      AppSettingsRoute.InternalRoute.BackupPlayground -> InternalSettingsFragmentDirections.actionInternalSettingsFragmentToInternalBackupPlaygroundFragment()
      AppSettingsRoute.InternalRoute.StorageServicePlayground -> InternalSettingsFragmentDirections.actionInternalSettingsFragmentToInternalStorageServicePlaygroundFragment()
      AppSettingsRoute.InternalRoute.SvrPlayground -> InternalSettingsFragmentDirections.actionInternalSettingsFragmentToInternalSvrPlaygroundFragment()
      AppSettingsRoute.InternalRoute.DataSeedingPlayground -> InternalSettingsFragmentDirections.actionInternalSettingsFragmentToDataSeedingPlaygroundFragment()
      AppSettingsRoute.InternalRoute.Search -> InternalSettingsFragmentDirections.actionInternalSettingsFragmentToInternalSearchFragment()
      AppSettingsRoute.InternalRoute.DonorErrorConfiguration -> InternalSettingsFragmentDirections.actionInternalSettingsFragmentToDonorErrorConfigurationFragment()
      AppSettingsRoute.InternalRoute.OneTimeDonationConfiguration -> InternalSettingsFragmentDirections.actionInternalSettingsFragmentToOneTimeDonationConfigurationFragment()
      AppSettingsRoute.InternalRoute.TerminalDonationConfiguration -> InternalSettingsFragmentDirections.actionInternalSettingsFragmentToTerminalDonationConfigurationFragment()
      AppSettingsRoute.InternalRoute.StoryDialogs -> InternalSettingsFragmentDirections.actionInternalSettingsFragmentToStoryDialogsLauncherFragment()
      AppSettingsRoute.InternalRoute.ChatSpringboard -> InternalSettingsFragmentDirections.actionInternalSettingsFragmentToInternalConversationSpringboardFragment()
      else -> error("Unexpected route: ${route.javaClass.name}")
    }

    findNavController().safeNavigate(directions)
  }

  private fun initializeSearch(view: View) {
    val toolbar: Toolbar = view.findViewById(R.id.toolbar)
    searchMenuItem = toolbar.menu.findItem(R.id.menu_search)

    val searchView: SearchView = searchMenuItem?.actionView as? SearchView ?: return
    val queryListener = object : SearchView.OnQueryTextListener {
      override fun onQueryTextSubmit(query: String?): Boolean {
        searchView.clearFocus()
        viewModel.setSearchQuery(query.orEmpty())
        return true
      }

      override fun onQueryTextChange(newText: String?): Boolean {
        viewModel.setSearchQuery(newText.orEmpty())
        return true
      }
    }

    searchView.maxWidth = Integer.MAX_VALUE
    searchView.queryHint = getString(R.string.CameraContacts__menu_search)

    searchMenuItem?.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
      override fun onMenuItemActionExpand(item: MenuItem): Boolean {
        searchView.setIncognitoKeyboardEnabled(SignalStore.settings.isIncognitoKeyboardEnabled)
        searchView.setOnQueryTextListener(queryListener)
        return true
      }

      override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
        searchView.setOnQueryTextListener(null)
        searchView.setQuery("", false)
        viewModel.setSearchQuery("")
        return true
      }
    })

    val currentQuery = viewModel.state.value?.searchQuery.orEmpty()
    if (currentQuery.isNotBlank() && searchMenuItem?.expandActionView() == true) {
      searchView.setQuery(currentQuery, false)
    }
  }

  private fun getConfiguration(state: InternalSettingsState): DSLConfiguration {
    return configure {
      sectionHeaderPref(DSLSettingsText.from(internalText("Account")))

      clickPref(
        title = DSLSettingsText.from(internalText("Refresh attributes")),
        summary = DSLSettingsText.from(internalText("Forces a write of capabilities on to the server followed by a read.")),
        onClick = {
          refreshAttributes()
        }
      )

      clickPref(
        title = DSLSettingsText.from(internalText("Refresh profile")),
        summary = DSLSettingsText.from(internalText("Forces a refresh of your own profile.")),
        onClick = {
          refreshProfile()
        }
      )

      clickPref(
        title = DSLSettingsText.from(internalText("Rotate profile key")),
        summary = DSLSettingsText.from(internalText("Creates a new versioned profile, and triggers an update of any GV2 group you belong to.")),
        onClick = {
          rotateProfileKey()
        }
      )

      clickPref(
        title = DSLSettingsText.from(internalText("Override remote config")),
        summary = DSLSettingsText.from(internalText("View every remote config flag and locally override any of them.")),
        onClick = {
          parentEventEmitter.navigateTo(AppSettingsRoute.InternalRoute.RemoteConfig)
        }
      )

      clickPref(
        title = DSLSettingsText.from(internalText("Refresh remote config")),
        summary = DSLSettingsText.from(internalText("Forces a refresh of the remote config locally instead of waiting for the elapsed time.")),
        onClick = {
          refreshRemoteValues()
        }
      )

      if (BuildConfig.DEBUG) {
        clickPref(
          title = DSLSettingsText.from(internalText("Export quickstart credentials")),
          summary = DSLSettingsText.from(internalText("Export registration credentials to a JSON file for quickstart builds.")),
          onClick = {
            exportQuickstartCredentials()
          }
        )
      }

      clickPref(
        title = DSLSettingsText.from(internalText("Copy service password")),
        summary = DSLSettingsText.from(internalText("Copy the password used to authenticate with the service.")),
        onClick = {
          onCopyServicePasswordClicked()
        }
      )

      clickPref(
        title = DSLSettingsText.from(internalText("Unregister")),
        summary = DSLSettingsText.from(internalText("This will unregister your account without deleting it.")),
        onClick = {
          onUnregisterClicked()
        }
      )

      clickPref(
        title = DSLSettingsText.from(internalText("Jump to message")),
        summary = DSLSettingsText.from(internalText("Find and jump to a message via its sentTimestamp.")),
        onClick = {
          promptUserForSentTimestamp()
        }
      )

      clickPref(
        title = DSLSettingsText.from(internalText("App Issues")),
        summary = DSLSettingsText.from(internalText("View recorded app issues, like slow reads and writes.")),
        onClick = {
          parentEventEmitter.navigateTo(AppSettingsRoute.InternalRoute.Issues)
        }
      )

      switchPref(
        title = DSLSettingsText.from(internalText("Disable internal user flag")),
        summary = DSLSettingsText.from(internalText("Experience life as a non-internal user. Force-stop the app to be an internal user again.")),
        isChecked = state.disableInternalUser,
        onClick = {
          viewModel.setDisableInternalUser(!state.disableInternalUser)
        }
      )

      dividerPref()

      sectionHeaderPref(DSLSettingsText.from(internalText("Playgrounds")))

      clickPref(
        title = DSLSettingsText.from(internalText("SQLite Playground")),
        summary = DSLSettingsText.from(internalText("Run raw SQLite queries.")),
        onClick = {
          parentEventEmitter.navigateTo(AppSettingsRoute.InternalRoute.SqlitePlayground)
        }
      )

      clickPref(
        title = DSLSettingsText.from(internalText("Backup Playground")),
        summary = DSLSettingsText.from(internalText("Test backup import/export.")),
        onClick = {
          parentEventEmitter.navigateTo(AppSettingsRoute.InternalRoute.BackupPlayground)
        }
      )

      clickPref(
        title = DSLSettingsText.from(internalText("Storage Service Playground")),
        summary = DSLSettingsText.from(internalText("Test and view storage service stuff.")),
        onClick = {
          parentEventEmitter.navigateTo(AppSettingsRoute.InternalRoute.StorageServicePlayground)
        }
      )

      clickPref(
        title = DSLSettingsText.from(internalText("SVR Playground")),
        summary = DSLSettingsText.from(internalText("Quickly test various SVR options and error conditions.")),
        onClick = {
          parentEventEmitter.navigateTo(AppSettingsRoute.InternalRoute.SvrPlayground)
        }
      )

      clickPref(
        title = DSLSettingsText.from(internalText("Data Seeding Playground")),
        summary = DSLSettingsText.from(internalText("Seed conversations with media files from a folder.")),
        onClick = {
          parentEventEmitter.navigateTo(AppSettingsRoute.InternalRoute.DataSeedingPlayground)
        }
      )

      dividerPref()

      sectionHeaderPref(DSLSettingsText.from(internalText("App UI")))

      clickPref(
        title = DSLSettingsText.from(internalText("Display enable permission sheet")),
        onClick = {
          PermissionDeniedBottomSheet.showPermissionFragment(
            titleRes = R.string.app_name,
            subtitleRes = R.string.app_name,
            useExtended = true
          ).show(parentFragmentManager, null)
        }
      )

      clickPref(
        title = DSLSettingsText.from(internalText("Display permission rationale dialog")),
        onClick = {
          RationaleDialog.createFor(requireContext(), internalText("Title"), internalText("Details"), R.drawable.symbol_key_24).show()
        }
      )

      clickPref(
        title = DSLSettingsText.from(internalText("Collapse chat updates")),
        summary = DSLSettingsText.from(internalText("Collapses certain consecutive chat updates - cannot be undone.")),
        onClick = {
          SignalStore.misc.completedCollapsedEventsMigration = false
          AppDependencies.jobManager.add(BackfillCollapsedMessageJob())
        }
      )

      clickPref(
        title = DSLSettingsText.from(internalText("Reset assistant screen security prompt")),
        summary = DSLSettingsText.from(internalText("Clears the 90-day cooldown after choosing to re-enable screenshots. Screen security must also be off for the prompt to show.")),
        onClick = {
          SignalStore.settings.assistantScreenSecurityDeclinedAt = 0
          Toast.makeText(requireContext(), internalText("Prompt reset"), Toast.LENGTH_SHORT).show()
        }
      )

      dividerPref()

      sectionHeaderPref(DSLSettingsText.from(internalText("Miscellaneous")))

      clickPref(
        title = DSLSettingsText.from(internalText("Search for a recipient")),
        summary = DSLSettingsText.from(internalText("Search by ID, ACI, or PNI.")),
        onClick = {
          parentEventEmitter.navigateTo(AppSettingsRoute.InternalRoute.Search)
        }
      )

      switchPref(
        title = DSLSettingsText.from(internalText("'Internal Details' button")),
        summary = DSLSettingsText.from(internalText("Show a button in conversation settings that lets you see more information about a user.")),
        isChecked = state.seeMoreUserDetails,
        onClick = {
          viewModel.setSeeMoreUserDetails(!state.seeMoreUserDetails)
        }
      )

      switchPref(
        title = DSLSettingsText.from(internalText("Shake to Report")),
        summary = DSLSettingsText.from(internalText("Shake your phone to easily submit and share a debug log.")),
        isChecked = state.shakeToReport,
        onClick = {
          viewModel.setShakeToReport(!state.shakeToReport)
        }
      )

      switchPref(
        title = DSLSettingsText.from(internalText("Show archive status hint")),
        summary = DSLSettingsText.from(internalText("Shows a color square based on archive status, green good, red bad.")),
        isChecked = state.showArchiveStateHint,
        onClick = {
          viewModel.setShowMediaArchiveStateHint(!state.showArchiveStateHint)
        }
      )

      clickPref(
        title = DSLSettingsText.from(internalText("Log dump PreKey ServiceId-KeyIds")),
        onClick = {
          logPreKeyIds()
        }
      )

      clickPref(
        title = DSLSettingsText.from(internalText("Force prekey rotation")),
        summary = DSLSettingsText.from(internalText("Clears the last forced refresh time and enqueues a forced PreKeysSyncJob, rotating all ACI and PNI prekeys.")),
        onClick = {
          SignalStore.misc.lastForcedPreKeyRefresh = 0
          AppDependencies.jobManager.add(PreKeysSyncJob.create(forceRotationRequested = true))
          Toast.makeText(requireContext(), internalText("Enqueued forced prekey rotation"), Toast.LENGTH_SHORT).show()
        }
      )

      clickPref(
        title = DSLSettingsText.from(internalText("Retry all jobs now")),
        summary = DSLSettingsText.from(internalText("Clear backoff intervals, app will restart")),
        onClick = {
          SimpleTask.run({
            JobDatabase.getInstance(AppDependencies.application).debugResetBackoffInterval()
          }) {
            AppUtil.restart(requireContext())
          }
        }
      )

      clickPref(
        title = DSLSettingsText.from(internalText("Delete all prekeys")),
        summary = DSLSettingsText.from(internalText("Deletes all signed/last-resort/one-time prekeys for both ACI and PNI accounts. WILL cause problems.")),
        onClick = {
          MaterialAlertDialogBuilder(requireContext())
            .setTitle(internalText("Delete all prekeys?"))
            .setMessage(internalText("Are you sure? This will delete all prekeys for both ACI and PNI accounts. This WILL cause problems."))
            .setPositiveButton(android.R.string.ok) { _, _ ->
              SignalDatabase.signedPreKeys.debugDeleteAll()
              SignalDatabase.oneTimePreKeys.debugDeleteAll()
              SignalDatabase.kyberPreKeys.debugDeleteAll()

              Toast.makeText(requireContext(), internalText("All prekeys deleted!"), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        }
      )

      clickPref(
        title = DSLSettingsText.from(internalText("Run self-check key transparency")),
        summary = DSLSettingsText.from(internalText("Automatically enqueues a job to run KT against yourself without waiting for the elapsed time.")),
        onClick = {
          CheckKeyTransparencyJob.enqueueIfNecessary(addDelay = false, force = true)
        }
      )

      clickPref(
        title = DSLSettingsText.from(internalText("Run unread reminder job")),
        summary = DSLSettingsText.from(internalText("Generates an unread reminder notification based on unreads and muted preferences. Skips the three day cooldown.")),
        onClick = {
          SignalDatabase.threads.getMutedThreadIds(0).forEach { threadId -> UnreadReminderJob.enqueue(threadId, 0) }
        }
      )

      switchPref(
        title = DSLSettingsText.from(internalText("Enable ANR-induced crashing")),
        isChecked = SignalStore.internal.anrDetectionCrashes,
        onClick = {
          SignalStore.internal.anrDetectionCrashes = !SignalStore.internal.anrDetectionCrashes
        }
      )

      dividerPref()

      sectionHeaderPref(DSLSettingsText.from(internalText("Logging")))

      clickPref(
        title = DSLSettingsText.from(internalText("Clear all logs")),
        onClick = {
          SimpleTask.run({
            LogDatabase.getInstance(requireActivity().application).logs.clearAll()
          }) {
            Toast.makeText(requireContext(), internalText("Cleared all logs"), Toast.LENGTH_SHORT).show()
          }
        }
      )

      clickPref(
        title = DSLSettingsText.from(internalText("Clear keep longer logs")),
        onClick = {
          clearKeepLongerLogs()
        }
      )

      clickPref(
        title = DSLSettingsText.from(internalText("Clear all crashes")),
        onClick = {
          SimpleTask.run({
            LogDatabase.getInstance(requireActivity().application).crashes.clear()
          }) {
            Toast.makeText(requireContext(), internalText("Cleared crashes"), Toast.LENGTH_SHORT).show()
          }
        }
      )

      clickPref(
        title = DSLSettingsText.from(internalText("Clear all ANRs")),
        onClick = {
          SimpleTask.run({
            LogDatabase.getInstance(requireActivity().application).anrs.clear()
          }) {
            Toast.makeText(requireContext(), internalText("Cleared ANRs"), Toast.LENGTH_SHORT).show()
          }
        }
      )

      clickPref(
        title = DSLSettingsText.from(internalText("Clear local metrics")),
        summary = DSLSettingsText.from(internalText("Click to clear all local metrics state.")),
        onClick = {
          clearAllLocalMetricsState()
        }
      )

      dividerPref()

      sectionHeaderPref(DSLSettingsText.from(internalText("Payments")))

      clickPref(
        title = DSLSettingsText.from(internalText("Copy payments data")),
        summary = DSLSettingsText.from(internalText("Copy all payment records to clipboard.")),
        onClick = {
          copyPaymentsDataToClipboard()
        }
      )

      dividerPref()

      sectionHeaderPref(DSLSettingsText.from(internalText("Storage Service")))

      switchPref(
        title = DSLSettingsText.from(internalText("Disable syncing")),
        summary = DSLSettingsText.from(internalText("Prevent syncing any data to/from storage service.")),
        isChecked = state.disableStorageService,
        onClick = {
          viewModel.setDisableStorageService(!state.disableStorageService)
        }
      )

      clickPref(
        title = DSLSettingsText.from(internalText("Sync now")),
        summary = DSLSettingsText.from(internalText("Enqueue a normal storage service sync.")),
        onClick = {
          enqueueStorageServiceSync()
        }
      )

      clickPref(
        title = DSLSettingsText.from(internalText("Overwrite remote data")),
        summary = DSLSettingsText.from(internalText("Forces remote storage to match the local device state.")),
        onClick = {
          enqueueStorageServiceForcePush()
        }
      )

      dividerPref()

      sectionHeaderPref(DSLSettingsText.from(internalText("Groups V2")))

      switchPref(
        title = DSLSettingsText.from(internalText("Force invites")),
        summary = DSLSettingsText.from(internalText("Members will not be added directly to a GV2 even if they could be.")),
        isChecked = state.gv2forceInvites,
        onClick = {
          viewModel.setGv2ForceInvites(!state.gv2forceInvites)
        }
      )

      switchPref(
        title = DSLSettingsText.from(internalText("Ignore P2P changes")),
        summary = DSLSettingsText.from(internalText("Changes sent P2P will be ignored. In conjunction with ignoring server changes, will cause passive voice.")),
        isChecked = state.gv2ignoreP2PChanges,
        onClick = {
          viewModel.setGv2IgnoreP2PChanges(!state.gv2ignoreP2PChanges)
        }
      )

      dividerPref()

      sectionHeaderPref(DSLSettingsText.from(internalText("Network")))

      switchPref(
        title = DSLSettingsText.from(internalText("Allow censorship circumvention toggle")),
        summary = DSLSettingsText.from(internalText("Allow changing the censorship circumvention toggle regardless of network connectivity.")),
        isChecked = state.allowCensorshipSetting,
        onClick = {
          viewModel.setAllowCensorshipSetting(!state.allowCensorshipSetting)
        }
      )

      dividerPref()

      sectionHeaderPref(DSLSettingsText.from(internalText("Conversations and Shortcuts")))

      clickPref(
        title = DSLSettingsText.from(internalText("Delete all dynamic shortcuts")),
        summary = DSLSettingsText.from(internalText("Click to delete all dynamic shortcuts")),
        onClick = {
          deleteAllDynamicShortcuts()
        }
      )

      dividerPref()

      sectionHeaderPref(DSLSettingsText.from(internalText("Emoji")))

      val emojiSummary = if (state.emojiVersion == null) {
        "Use built-in emoji set"
      } else {
        "Current version: ${state.emojiVersion.version} at density ${state.emojiVersion.density}"
      }

      switchPref(
        title = DSLSettingsText.from(internalText("Use built-in emoji set")),
        summary = DSLSettingsText.from(emojiSummary),
        isChecked = state.useBuiltInEmojiSet,
        onClick = {
          viewModel.setUseBuiltInEmoji(!state.useBuiltInEmojiSet)
        }
      )

      clickPref(
        title = DSLSettingsText.from(internalText("Force emoji download")),
        summary = DSLSettingsText.from(internalText("Download the latest emoji set if it's newer than what we have.")),
        onClick = {
          AppDependencies.jobManager.add(DownloadLatestEmojiDataJob(true))
        }
      )

      clickPref(
        title = DSLSettingsText.from(internalText("Force search index download")),
        summary = DSLSettingsText.from(internalText("Download the latest emoji search index if it's newer than what we have.")),
        onClick = {
          EmojiSearchIndexDownloadJob.scheduleImmediately()
        }
      )

      dividerPref()

      sectionHeaderPref(DSLSettingsText.from(internalText("Sender Key")))

      clickPref(
        title = DSLSettingsText.from(internalText("Clear all state")),
        summary = DSLSettingsText.from(internalText("Click to delete all sender key state")),
        onClick = {
          clearAllSenderKeyState()
        }
      )

      clickPref(
        title = DSLSettingsText.from(internalText("Clear shared state")),
        summary = DSLSettingsText.from(internalText("Click to delete all sharing state")),
        onClick = {
          clearAllSenderKeySharedState()
        }
      )

      switchPref(
        title = DSLSettingsText.from(internalText("Remove 2 person minimum")),
        summary = DSLSettingsText.from(internalText("Remove the requirement that you  need at least 2 recipients to use sender key.")),
        isChecked = state.removeSenderKeyMinimium,
        onClick = {
          viewModel.setRemoveSenderKeyMinimum(!state.removeSenderKeyMinimium)
        }
      )

      switchPref(
        title = DSLSettingsText.from(internalText("Delay resends")),
        summary = DSLSettingsText.from(internalText("Delay resending messages in response to retry receipts by 10 seconds.")),
        isChecked = state.delayResends,
        onClick = {
          viewModel.setDelayResends(!state.delayResends)
        }
      )

      dividerPref()

      sectionHeaderPref(DSLSettingsText.from(internalText("Group call server")))

      radioPref(
        title = DSLSettingsText.from(internalText("Production server")),
        summary = DSLSettingsText.from(BuildConfig.SIGNAL_SFU_URL),
        isChecked = state.callingServer == BuildConfig.SIGNAL_SFU_URL,
        onClick = {
          viewModel.setInternalGroupCallingServer(BuildConfig.SIGNAL_SFU_URL)
        }
      )

      BuildConfig.SIGNAL_SFU_INTERNAL_NAMES.zip(BuildConfig.SIGNAL_SFU_INTERNAL_URLS)
        .forEach { (name, server) ->
          radioPref(
            title = DSLSettingsText.from(internalText("$name server", "$name servidor")),
            summary = DSLSettingsText.from(server),
            isChecked = state.callingServer == server,
            onClick = {
              viewModel.setInternalGroupCallingServer(server)
            }
          )
        }

      sectionHeaderPref(DSLSettingsText.from(internalText("Calling options")))

      clickPref(
        title = DSLSettingsText.from(internalText("Display call quality survey")),
        onClick = {
          CallQualityBottomSheetFragment
            .create(SubmitCallQualitySurveyRequest())
            .show(parentFragmentManager, BottomSheetUtil.STANDARD_BOTTOM_SHEET_FRAGMENT_TAG)
        }
      )

      radioListPref(
        title = DSLSettingsText.from(internalText("Bandwidth mode")),
        listItems = CallManager.DataMode.entries.map { it.name }.toTypedArray(),
        selected = CallManager.DataMode.entries.indexOf(state.callingDataMode),
        onSelected = {
          viewModel.setInternalCallingDataMode(CallManager.DataMode.entries[it])
        }
      )

      switchPref(
        title = DSLSettingsText.from(internalText("Use Telecom integration")),
        isChecked = state.callingUseTelecom,
        onClick = {
          viewModel.setInternalCallingUseTelecom(!state.callingUseTelecom)
        }
      )

      switchPref(
        title = DSLSettingsText.from(internalText("Set Audio Config:")),
        isChecked = state.callingSetAudioConfig,
        onClick = {
          viewModel.setInternalCallingSetAudioConfig(!state.callingSetAudioConfig)
        }
      )

      switchPref(
        title = DSLSettingsText.from(internalText("    Use Oboe ADM")),
        isChecked = state.callingUseOboeAdm,
        isEnabled = state.callingSetAudioConfig,
        onClick = {
          viewModel.setInternalCallingUseOboeAdm(!state.callingUseOboeAdm)
        }
      )

      switchPref(
        title = DSLSettingsText.from(internalText("    Use Software AEC")),
        isChecked = state.callingUseSoftwareAec,
        isEnabled = state.callingSetAudioConfig,
        onClick = {
          viewModel.setInternalCallingUseSoftwareAec(!state.callingUseSoftwareAec)
        }
      )

      switchPref(
        title = DSLSettingsText.from(internalText("    Use Software NS")),
        isChecked = state.callingUseSoftwareNs,
        isEnabled = state.callingSetAudioConfig,
        onClick = {
          viewModel.setInternalCallingUseSoftwareNs(!state.callingUseSoftwareNs)
        }
      )

      switchPref(
        title = DSLSettingsText.from(internalText("    Use Input Low Latency")),
        isChecked = state.callingUseInputLowLatency,
        isEnabled = state.callingSetAudioConfig,
        onClick = {
          viewModel.setInternalCallingUseInputLowLatency(!state.callingUseInputLowLatency)
        }
      )

      switchPref(
        title = DSLSettingsText.from(internalText("    Use Input Voice Comm")),
        isChecked = state.callingUseInputVoiceComm,
        isEnabled = state.callingSetAudioConfig,
        onClick = {
          viewModel.setInternalCallingUseInputVoiceComm(!state.callingUseInputVoiceComm)
        }
      )

      switchPref(
        title = DSLSettingsText.from(internalText("Set Video Config:")),
        isChecked = state.callingSetVideoConfig,
        onClick = {
          viewModel.setInternalCallingSetVideoConfig(!state.callingSetVideoConfig)
        }
      )

      switchPref(
        title = DSLSettingsText.from(internalText("    Use Hardware Vp9 Encode")),
        isChecked = state.callingUseHardwareVp9Encode,
        isEnabled = state.callingSetVideoConfig,
        onClick = {
          viewModel.setInternalCallingUseHardwareVp9Encode(!state.callingUseHardwareVp9Encode)
        }
      )

      switchPref(
        title = DSLSettingsText.from(internalText("    Use Hardware Vp9 Decode")),
        isChecked = state.callingUseHardwareVp9Decode,
        isEnabled = state.callingSetVideoConfig,
        onClick = {
          viewModel.setInternalCallingUseHardwareVp9Decode(!state.callingUseHardwareVp9Decode)
        }
      )

      switchPref(
        title = DSLSettingsText.from(internalText("    Use Software Vp9 Encode")),
        isChecked = state.callingUseSoftwareVp9Encode,
        isEnabled = state.callingSetVideoConfig,
        onClick = {
          viewModel.setInternalCallingUseSoftwareVp9Encode(!state.callingUseSoftwareVp9Encode)
        }
      )

      switchPref(
        title = DSLSettingsText.from(internalText("    Use Software Vp9 Decode")),
        isChecked = state.callingUseSoftwareVp9Decode,
        isEnabled = state.callingSetVideoConfig,
        onClick = {
          viewModel.setInternalCallingUseSoftwareVp9Decode(!state.callingUseSoftwareVp9Decode)
        }
      )

      switchPref(
        title = DSLSettingsText.from(internalText("    Use Hardware H265 Encode")),
        isChecked = state.callingUseHardwareH265Encode,
        isEnabled = state.callingSetVideoConfig,
        onClick = {
          viewModel.setInternalCallingUseHardwareH265Encode(!state.callingUseHardwareH265Encode)
        }
      )

      switchPref(
        title = DSLSettingsText.from(internalText("    Use Hardware H265 Decode")),
        isChecked = state.callingUseHardwareH265Decode,
        isEnabled = state.callingSetVideoConfig,
        onClick = {
          viewModel.setInternalCallingUseHardwareH265Decode(!state.callingUseHardwareH265Decode)
        }
      )

      switchPref(
        title = DSLSettingsText.from(internalText("Enable SVC")),
        isChecked = state.callingEnableSvc,
        onClick = {
          viewModel.setInternalCallingEnableSvc(!state.callingEnableSvc)
        }
      )

      clickPref(
        title = DSLSettingsText.from(internalText("Stats Interval (secs)")),
        summary = DSLSettingsText.from(if (state.callingStatsIntervalSecs > 0) state.callingStatsIntervalSecs.toString() else "Default"),
        onClick = {
          promptUserForInt(
            title = internalText("Stats Interval (secs)"),
            message = internalText("How often RingRTC should report call stats. Leave blank or enter 0 to use the default interval."),
            initialValue = state.callingStatsIntervalSecs.takeIf { it > 0 }
          ) { intervalSecs ->
            viewModel.setInternalCallingStatsIntervalSecs(intervalSecs ?: 0)
          }
        }
      )

      clickPref(
        title = DSLSettingsText.from(internalText("Minimum Capture FPS")),
        summary = DSLSettingsText.from(
          buildString {
            append(if (state.callingMinimumCaptureFps > 0) "${state.callingMinimumCaptureFps} fps" else "Default")
            cameraFpsRangeDescription?.let { append("\n$it") }
          }
        ),
        onClick = {
          promptUserForInt(
            title = internalText("Minimum Capture FPS"),
            message = internalText("Floor for the camera's capture framerate range. 0 for default logic. Applies on next call start."),
            initialValue = state.callingMinimumCaptureFps.takeIf { it > 0 }
          ) { minimumFps ->
            viewModel.setInternalCallingMinimumCaptureFps(minimumFps ?: 0)
          }
        }
      )

      dividerPref()

      // TODO [alex] -- db access on main thread!
      if (InAppPaymentsRepository.getSubscriber(InAppPaymentSubscriberRecord.Type.DONATION) != null) {
        sectionHeaderPref(DSLSettingsText.from(internalText("Badges")))

        clickPref(
          title = DSLSettingsText.from(internalText("Enqueue redemption.")),
          onClick = {
            enqueueSubscriptionRedemption()
          }
        )

        clickPref(
          title = DSLSettingsText.from(internalText("Enqueue keep-alive.")),
          onClick = {
            enqueueSubscriptionKeepAlive()
          }
        )

        clickPref(
          title = DSLSettingsText.from(internalText("Set error state.")),
          onClick = {
            parentEventEmitter.navigateTo(AppSettingsRoute.InternalRoute.DonorErrorConfiguration)
          }
        )

        clickPref(
          title = DSLSettingsText.from(internalText("Clear keep-alive timestamps")),
          onClick = {
            SignalStore.inAppPayments.setLastEndOfPeriod(0L)
            Toast.makeText(context, internalText("Cleared"), Toast.LENGTH_SHORT).show()
          }
        )
        dividerPref()
      }

      if (state.hasPendingOneTimeDonation) {
        clickPref(
          title = DSLSettingsText.from(internalText("Clear pending one-time donation.")),
          onClick = {
            SignalStore.inAppPayments.setPendingOneTimeDonation(null)
          }
        )
      } else {
        clickPref(
          title = DSLSettingsText.from(internalText("Set pending one-time donation.")),
          onClick = {
            parentEventEmitter.navigateTo(AppSettingsRoute.InternalRoute.OneTimeDonationConfiguration)
          }
        )
      }

      clickPref(
        title = DSLSettingsText.from(internalText("Enqueue terminal donation")),
        onClick = {
          parentEventEmitter.navigateTo(AppSettingsRoute.InternalRoute.TerminalDonationConfiguration)
        }
      )

      dividerPref()

      sectionHeaderPref(DSLSettingsText.from(internalText("Release channel")))

      clickPref(
        title = DSLSettingsText.from(internalText("Set last version seen back 10 versions")),
        onClick = {
          SignalStore.releaseChannel.highestVersionNoteReceived = max(SignalStore.releaseChannel.highestVersionNoteReceived - 10, 0)
        }
      )

      clickPref(
        title = DSLSettingsText.from(internalText("Reset donation megaphone")),
        onClick = {
          SignalDatabase.remoteMegaphones.debugRemoveAll()
          MegaphoneDatabase.getInstance(AppDependencies.application).let {
            it.delete(Megaphones.Event.REMOTE_MEGAPHONE)
            it.markFirstVisible(Megaphones.Event.DONATE_Q2_2022, System.currentTimeMillis() - TimeUnit.DAYS.toMillis(31))
          }
          // Force repository database cache refresh
          MegaphoneRepository(AppDependencies.application).onFirstEverAppLaunch()
        }
      )

      clickPref(
        title = DSLSettingsText.from(internalText("Fetch release channel")),
        onClick = {
          SignalStore.releaseChannel.previousManifestMd5 = ByteArray(0)
          RetrieveRemoteAnnouncementsJob.enqueue(force = true)
        }
      )

      clickPref(
        title = DSLSettingsText.from(internalText("Add sample note")),
        onClick = {
          viewModel.addSampleReleaseNote()
        }
      )

      clickPref(
        title = DSLSettingsText.from(internalText("Add remote backups note")),
        onClick = {
          viewModel.addSampleReleaseNote("remote_backups")
        }
      )

      clickPref(
        title = DSLSettingsText.from(internalText("Add remote donate megaphone")),
        onClick = {
          viewModel.addRemoteDonateMegaphone()
        }
      )

      clickPref(
        title = DSLSettingsText.from(internalText("Add donate_friend remote megaphone")),
        onClick = {
          viewModel.addRemoteDonateFriendMegaphone()
        }
      )

      dividerPref()

      sectionHeaderPref(DSLSettingsText.from(internalText("CDS")))

      clickPref(
        title = DSLSettingsText.from(internalText("Clear history")),
        summary = DSLSettingsText.from(internalText("Clears all CDS history, meaning the next sync will consider all numbers to be new.")),
        onClick = {
          clearCdsHistory()
        }
      )

      clickPref(
        title = DSLSettingsText.from(internalText("Clear all service IDs")),
        summary = DSLSettingsText.from(internalText("Clears all known service IDs (except your own) for people that have phone numbers. Do not use on your personal device!")),
        onClick = {
          clearAllServiceIds()
        }
      )

      clickPref(
        title = DSLSettingsText.from(internalText("Clear all profile keys")),
        summary = DSLSettingsText.from(internalText("Clears all known profile keys (except your own). Do not use on your personal device!")),
        onClick = {
          clearAllProfileKeys()
        }
      )

      dividerPref()

      sectionHeaderPref(DSLSettingsText.from(internalText("Stories")))

      clickPref(
        title = DSLSettingsText.from(internalText("Clear onboarding state")),
        summary = DSLSettingsText.from(internalText("Clears onboarding flag and triggers download of onboarding stories.")),
        isEnabled = state.canClearOnboardingState,
        onClick = {
          viewModel.onClearOnboardingState()
        }
      )

      clickPref(
        title = DSLSettingsText.from(internalText("Clear choose initial my story privacy state")),
        isEnabled = true,
        onClick = {
          SignalStore.story.userHasBeenNotifiedAboutStories = false
        }
      )

      clickPref(
        title = DSLSettingsText.from(internalText("Clear first time navigation state")),
        isEnabled = true,
        onClick = {
          SignalStore.story.userHasSeenFirstNavView = false
        }
      )

      clickPref(
        title = DSLSettingsText.from(internalText("Stories dialog launcher")),
        onClick = {
          parentEventEmitter.navigateTo(AppSettingsRoute.InternalRoute.StoryDialogs)
        }
      )

      dividerPref()

      sectionHeaderPref(DSLSettingsText.from(internalText("PNP")))

      clickPref(
        title = DSLSettingsText.from(internalText("Reset 'PNP initialized' state")),
        summary = DSLSettingsText.from(internalText("Current initialized state: ${state.pnpInitialized}", "Estado atual de inicialização: ${state.pnpInitialized}")),
        isEnabled = state.pnpInitialized,
        onClick = {
          viewModel.resetPnpInitializedState()
        }
      )

      clickPref(
        title = DSLSettingsText.from(internalText("Corrupt username")),
        summary = DSLSettingsText.from(internalText("Changes our local username without telling the server so it falls out of sync. Refresh profile afterwards to trigger corruption.")),
        onClick = {
          MaterialAlertDialogBuilder(requireContext())
            .setTitle(internalText("Corrupt your username?"))
            .setMessage(internalText("Are you sure? You might not be able to get your original username back."))
            .setPositiveButton(android.R.string.ok) { _, _ ->
              val random = "${(1..5).map { ('a'..'z').random() }.joinToString(separator = "") }.${Random.nextInt(10, 100)}"

              SignalStore.account.username = random
              SignalDatabase.recipients.setUsername(Recipient.self().id, random)
              StorageSyncHelper.scheduleSyncForDataChange()

              Toast.makeText(context, internalText("Done"), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel) { d, _ -> d.dismiss() }
            .show()
        }
      )

      clickPref(
        title = DSLSettingsText.from(internalText("Corrupt username link")),
        summary = DSLSettingsText.from(internalText("Changes our local username link without telling the server so it falls out of sync. Refresh profile afterwards to trigger corruption.")),
        onClick = {
          MaterialAlertDialogBuilder(requireContext())
            .setTitle(internalText("Corrupt your username link?"))
            .setMessage(internalText("Are you sure? You'll have to reset your link."))
            .setPositiveButton(android.R.string.ok) { _, _ ->
              SignalStore.account.usernameLink = UsernameLinkComponents(
                entropy = Util.getSecretBytes(32),
                serverId = SignalStore.account.usernameLink?.serverId ?: UUID.randomUUID()
              )
              StorageSyncHelper.scheduleSyncForDataChange()
              Toast.makeText(context, internalText("Done"), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel) { d, _ -> d.dismiss() }
            .show()
        }
      )

      dividerPref()
      sectionHeaderPref(DSLSettingsText.from(internalText("Chat Filters")))
      clickPref(
        title = DSLSettingsText.from(internalText("Reset pull to refresh tip count")),
        onClick = {
          SignalStore.uiHints.resetNeverDisplayPullToRefreshCount()
        }
      )

      dividerPref()
      clickPref(
        title = DSLSettingsText.from(internalText("Launch Conversation Test Springboard ")),
        onClick = {
          parentEventEmitter.navigateTo(AppSettingsRoute.InternalRoute.ChatSpringboard)
        }
      )

      switchPref(
        title = DSLSettingsText.from(internalText("Use V2 ConversationItem for Media")),
        isChecked = state.useConversationItemV2ForMedia,
        onClick = {
          viewModel.setUseConversationItemV2Media(!state.useConversationItemV2ForMedia)
        }
      )
    }
  }

  private fun onUnregisterClicked() {
    MaterialAlertDialogBuilder(requireContext())
      .setTitle(internalText("Unregister?"))
      .setMessage(internalText("Are you sure? You'll have to re-register to use Signal again -- no promises that the process will go smoothly."))
      .setPositiveButton(android.R.string.ok) { _, _ ->
        lifecycleScope.launch {
          when (AdvancedPrivacySettingsRepository.disablePushMessages()) {
            AdvancedPrivacySettingsRepository.DisablePushMessagesResult.SUCCESS -> {
              SignalStore.account.setRegistered(false)
              SignalStore.registration.clearRegistrationComplete()
              SignalStore.registration.hasUploadedProfile = false
              Toast.makeText(context, internalText("Unregistered!"), Toast.LENGTH_SHORT).show()
            }

            AdvancedPrivacySettingsRepository.DisablePushMessagesResult.NETWORK_ERROR -> {
              Toast.makeText(context, internalText("Network error!"), Toast.LENGTH_SHORT).show()
            }
          }
        }
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  private fun onCopyServicePasswordClicked() {
    val servicePassword = SignalStore.account.servicePassword
    if (servicePassword == null) {
      Toast.makeText(requireContext(), internalText("No service password set!"), Toast.LENGTH_SHORT).show()
      return
    }

    MaterialAlertDialogBuilder(requireContext())
      .setTitle(internalText("Copy service password?"))
      .setMessage(internalText("Your service password lets anyone who has it send messages as you on the service. Treat it like a password: don't paste it anywhere you don't fully trust. It will be cleared from the clipboard after ${Util.SENSITIVE_CLIPBOARD_TIMEOUT_SECONDS} seconds.", "Sua senha de serviço permite que qualquer pessoa que a tenha envie mensagens em seu nome. Trate-a como uma senha: não a cole em locais que não sejam confiáveis. Ela será removida da área de transferência após ${Util.SENSITIVE_CLIPBOARD_TIMEOUT_SECONDS} segundos."))
      .setPositiveButton(internalText("Copy")) { _, _ ->
        Util.copyToClipboardSensitive(requireContext(), servicePassword)
        Toast.makeText(requireContext(), internalText("Copied service password"), Toast.LENGTH_SHORT).show()
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  private fun copyPaymentsDataToClipboard() {
    MaterialAlertDialogBuilder(requireContext())
      .setMessage(
        """
    Local payments history will be copied to the clipboard.
    It may therefore compromise privacy.
    However, no private keys will be copied.
        """.trimIndent()
      )
      .setPositiveButton(
        "Copy"
      ) { _: DialogInterface?, _: Int ->
        val context: Context = AppDependencies.application
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        SimpleTask.run<Any?>(
          SignalExecutors.UNBOUNDED,
          {
            val tsv = DataExportUtil.createTsv()
            val clip = ClipData.newPlainText(context.getString(R.string.app_name), tsv)
            clipboard.setPrimaryClip(clip)
            null
          },
          {
            Toast.makeText(
              context,
              "Payments have been copied",
              Toast.LENGTH_SHORT
            ).show()
          }
        )
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  private fun refreshAttributes() {
    AppDependencies.jobManager
      .startChain(RefreshAttributesJob())
      .then(RefreshOwnProfileJob())
      .enqueue()
    Toast.makeText(context, internalText("Scheduled attribute refresh"), Toast.LENGTH_SHORT).show()
  }

  private fun refreshProfile() {
    AppDependencies.jobManager.add(RefreshOwnProfileJob())
    Toast.makeText(context, internalText("Scheduled profile refresh"), Toast.LENGTH_SHORT).show()
  }

  private fun rotateProfileKey() {
    AppDependencies.jobManager.add(RotateProfileKeyJob())
    Toast.makeText(context, internalText("Scheduled profile key rotation"), Toast.LENGTH_SHORT).show()
  }

  private fun refreshRemoteValues() {
    val starterToast = Toast.makeText(context, internalText("Running remote config refresh, app will restart after completion."), Toast.LENGTH_LONG).apply { show() }
    lifecycleScope.launch(Dispatchers.IO) {
      SignalStore.remoteConfig.eTag = ""
      val result: Optional<JobTracker.JobState> = AppDependencies.jobManager.runSynchronously(RemoteConfigRefreshJob(), TimeUnit.SECONDS.toMillis(10))

      withContext(Dispatchers.Main) {
        starterToast.cancel()
        if (result.isPresent && result.get() == JobTracker.JobState.SUCCESS) {
          val toast = Toast.makeText(context, internalText("Refresh successful. Restarting..."), Toast.LENGTH_SHORT)
          if (Build.VERSION.SDK_INT >= 30) {
            toast.addCallback(object : Toast.Callback() {
              override fun onToastHidden() {
                AppUtil.restart(requireContext())
              }
            })
          }
          toast.show()
        } else {
          Toast.makeText(context, internalText("Failed to refresh config remote config."), Toast.LENGTH_SHORT).show()
        }
      }
    }
  }

  private fun enqueueStorageServiceSync() {
    StorageSyncHelper.scheduleSyncForDataChange()
    Toast.makeText(context, internalText("Scheduled routine storage sync"), Toast.LENGTH_SHORT).show()
  }

  private fun enqueueStorageServiceForcePush() {
    AppDependencies.jobManager.add(StorageForcePushJob())
    Toast.makeText(context, internalText("Scheduled storage force push"), Toast.LENGTH_SHORT).show()
  }

  private fun deleteAllDynamicShortcuts() {
    ConversationUtil.clearAllShortcuts(requireContext())
    Toast.makeText(context, internalText("Deleted all dynamic shortcuts."), Toast.LENGTH_SHORT).show()
  }

  private fun clearAllSenderKeyState() {
    SignalDatabase.senderKeys.deleteAll()
    SignalDatabase.senderKeyShared.deleteAll()
    Toast.makeText(context, internalText("Deleted all sender key state."), Toast.LENGTH_SHORT).show()
  }

  private fun clearAllSenderKeySharedState() {
    SignalDatabase.senderKeyShared.deleteAll()
    Toast.makeText(context, internalText("Deleted all sender key shared state."), Toast.LENGTH_SHORT).show()
  }

  private fun clearAllLocalMetricsState() {
    LocalMetricsDatabase.getInstance(AppDependencies.application).clear()
    Toast.makeText(context, internalText("Cleared all local metrics state."), Toast.LENGTH_SHORT).show()
  }

  private fun enqueueSubscriptionRedemption() {
    viewModel.enqueueSubscriptionRedemption()
  }

  private fun enqueueSubscriptionKeepAlive() {
    InAppPaymentKeepAliveJob.enqueueAndTrackTime(System.currentTimeMillis().milliseconds)
  }

  private fun clearCdsHistory() {
    SignalDatabase.cds.clearAll()
    SignalStore.misc.cdsToken = null
    Toast.makeText(context, internalText("Cleared all CDS history."), Toast.LENGTH_SHORT).show()
  }

  private fun clearAllServiceIds() {
    MaterialAlertDialogBuilder(requireContext())
      .setTitle(internalText("Clear all serviceIds?"))
      .setMessage(internalText("Are you sure? Never do this on a non-test device."))
      .setPositiveButton(android.R.string.ok) { _, _ ->
        SignalDatabase.recipients.debugClearServiceIds()
        Toast.makeText(context, internalText("Cleared all service IDs."), Toast.LENGTH_SHORT).show()
      }
      .setNegativeButton(android.R.string.cancel) { d, _ ->
        d.dismiss()
      }
      .show()
  }

  private fun clearAllProfileKeys() {
    MaterialAlertDialogBuilder(requireContext())
      .setTitle(internalText("Clear all profile keys?"))
      .setMessage(internalText("Are you sure? Never do this on a non-test device."))
      .setPositiveButton(android.R.string.ok) { _, _ ->
        SignalDatabase.recipients.debugClearProfileData()
        Toast.makeText(context, internalText("Cleared all profile keys."), Toast.LENGTH_SHORT).show()
      }
      .setNegativeButton(android.R.string.cancel) { d, _ ->
        d.dismiss()
      }
      .show()
  }

  private fun clearKeepLongerLogs() {
    SimpleTask.run({
      LogDatabase.getInstance(requireActivity().application).logs.clearKeepLonger()
    }) {
      Toast.makeText(requireContext(), internalText("Cleared keep longer logs"), Toast.LENGTH_SHORT).show()
    }
  }

  private fun logPreKeyIds() {
    SimpleTask.run({
      val oneTimePreKeys = SignalDatabase.rawDatabase
        .query("SELECT * FROM ${OneTimePreKeyTable.TABLE_NAME}")
        .readToList { c ->
          c.requireString(OneTimePreKeyTable.ACCOUNT_ID) to c.requireLong(OneTimePreKeyTable.KEY_ID)
        }
        .joinToString()

      Log.i(TAG, "One-Time Prekeys\n$oneTimePreKeys")
    }) {
      Toast.makeText(requireContext(), internalText("Dumped to logs"), Toast.LENGTH_SHORT).show()
    }
  }

  private fun exportQuickstartCredentials() {
    MaterialAlertDialogBuilder(requireContext())
      .setTitle(internalText("Export quickstart credentials?"))
      .setMessage(internalText("This will export your account's private keys and credentials to an unencrypted file on disk. This is very dangerous! Only use it with test accounts."))
      .setPositiveButton(internalText("Export")) { _, _ ->
        SimpleTask.run({
          QuickstartCredentialExporter.export(requireContext())
        }) { file ->
          Toast.makeText(requireContext(), internalText("Exported to ${file.absolutePath}", "Exportado para ${file.absolutePath}"), Toast.LENGTH_LONG).show()
        }
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  /**
   * [onConfirmed] is given the exact contents of the input field
   */
  private fun promptUserForString(
    title: String,
    message: String? = null,
    initialValue: String = "",
    numeric: Boolean = false,
    onConfirmed: (String) -> Unit
  ) {
    val input = EditText(requireContext()).apply {
      inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
      gravity = Gravity.CENTER
      setText(initialValue)
      setSelection(initialValue.length)
    }

    MaterialAlertDialogBuilder(requireContext())
      .setTitle(title)
      .setMessage(message)
      .setView(input)
      .setPositiveButton(android.R.string.ok) { _, _ ->
        onConfirmed(input.text.toString())
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  /**
   * [onConfirmed] is given null if input is whitespace or if input could not be parsed
   */
  private fun promptUserForInt(
    title: String,
    message: String? = null,
    initialValue: Int? = null,
    onConfirmed: (Int?) -> Unit
  ) {
    promptUserForString(
      title = title,
      message = message,
      initialValue = initialValue?.toString() ?: "",
      numeric = true
    ) { text ->
      val value = text.trim().toIntOrNull()
      if (value == null) {
        Toast.makeText(requireContext(), internalText("Failed to parse number!"), Toast.LENGTH_SHORT).show()
      }

      onConfirmed(value)
    }
  }

  private fun promptUserForSentTimestamp() {
    promptUserForString(title = internalText("Enter sentTimestamp"), numeric = true) { text ->
      val number = text.toLongOrNull()
      if (number == null) {
        Toast.makeText(requireContext(), internalText("Failed to parse timestamp!"), Toast.LENGTH_SHORT).show()
        return@promptUserForString
      }

      val messages = SignalDatabase.messages.getMessagesBySentTimestamp(number)
      if (messages.isEmpty()) {
        Toast.makeText(requireContext(), internalText("Could not find a message with that timestamp!"), Toast.LENGTH_SHORT).show()
        return@promptUserForString
      }

      if (messages.size > 1) {
        Toast.makeText(requireContext(), internalText("There's ${messages.size} messages with that timestamp! Go run SQL or something.", "Há ${messages.size} mensagens com esse horário. Consulte o banco de dados para diferenciá-las."), Toast.LENGTH_SHORT).show()
        return@promptUserForString
      }

      val message: MessageRecord = messages[0]
      val startingPosition = SignalDatabase.messages.getMessagePositionInConversation(message.threadId, message.dateReceived)
      val threadRecipientId = SignalDatabase.threads.getRecipientIdForThreadId(message.threadId) ?: return@promptUserForString
      val intent = ConversationIntents
        .createBuilderSync(requireContext(), threadRecipientId, message.threadId)
        .withStartingPosition(startingPosition)
        .build()

      startActivity(intent)
    }
  }

  private fun internalText(english: String, portuguese: String? = null): String {
    if (org.signal.locale.AppLocale.resolvedLocale.language != "pt") return english
    return portuguese ?: PORTUGUESE_INTERNAL_TEXT[english] ?: english
  }

  private val PORTUGUESE_INTERNAL_TEXT = mapOf(
    "Account" to "Conta",
    "Refresh attributes" to "Atualizar atributos",
    "Forces a write of capabilities on to the server followed by a read." to "Envia os recursos da conta ao servidor e depois os consulta.",
    "Refresh profile" to "Atualizar perfil",
    "Forces a refresh of your own profile." to "Força a atualização do seu perfil.",
    "Rotate profile key" to "Renovar chave do perfil",
    "Creates a new versioned profile, and triggers an update of any GV2 group you belong to." to "Cria uma nova versão do perfil e atualiza os grupos GV2 de que você participa.",
    "Override remote config" to "Substituir configuração remota",
    "View every remote config flag and locally override any of them." to "Exibe todas as opções de configuração remota e permite substituí-las neste aparelho.",
    "Refresh remote config" to "Atualizar configuração remota",
    "Forces a refresh of the remote config locally instead of waiting for the elapsed time." to "Atualiza a configuração remota agora, sem esperar o intervalo normal.",
    "Export quickstart credentials" to "Exportar credenciais de início rápido",
    "Export registration credentials to a JSON file for quickstart builds." to "Exporta as credenciais de cadastro para um arquivo JSON de versões de início rápido.",
    "Copy service password" to "Copiar senha do serviço",
    "Copy the password used to authenticate with the service." to "Copia a senha usada para autenticar no serviço.",
    "Unregister" to "Cancelar cadastro",
    "This will unregister your account without deleting it." to "Cancela o cadastro da conta sem excluí-la.",
    "Jump to message" to "Ir para mensagem",
    "Find and jump to a message via its sentTimestamp." to "Localiza uma mensagem pelo horário de envio e abre a conversa nela.",
    "App Issues" to "Problemas do app",
    "View recorded app issues, like slow reads and writes." to "Exibe problemas registrados no app, como leituras e gravações lentas.",
    "Disable internal user flag" to "Desativar modo de usuário interno",
    "Experience life as a non-internal user. Force-stop the app to be an internal user again." to "Veja o app como um usuário comum. Para voltar ao modo interno, force o encerramento do app.",
    "Playgrounds" to "Ferramentas de teste",
    "SQLite Playground" to "Ferramenta de teste do SQLite",
    "Run raw SQLite queries." to "Executa consultas SQL diretamente no banco SQLite.",
    "Backup Playground" to "Ferramenta de teste de backup",
    "Test backup import/export." to "Testa a importação e exportação de backups.",
    "Storage Service Playground" to "Ferramenta de teste do serviço de armazenamento",
    "Test and view storage service stuff." to "Testa e exibe informações do serviço de armazenamento.",
    "SVR Playground" to "Ferramenta de teste do SVR",
    "Quickly test various SVR options and error conditions." to "Testa opções do SVR e situações de erro.",
    "Data Seeding Playground" to "Ferramenta de teste de dados",
    "Seed conversations with media files from a folder." to "Preenche conversas com arquivos de mídia de uma pasta.",
    "App UI" to "Interface do app",
    "Display enable permission sheet" to "Exibir tela de ativação de permissão",
    "Display permission rationale dialog" to "Exibir explicação sobre a permissão",
    "Title" to "Título",
    "Details" to "Detalhes",
    "Collapse chat updates" to "Agrupar atualizações da conversa",
    "Collapses certain consecutive chat updates - cannot be undone." to "Agrupa algumas atualizações consecutivas da conversa. Não é possível desfazer.",
    "Reset assistant screen security prompt" to "Redefinir aviso de segurança da tela",
    "Clears the 90-day cooldown after choosing to re-enable screenshots. Screen security must also be off for the prompt to show." to "Remove o período de espera de 90 dias após reativar capturas de tela. A segurança de tela também precisa estar desativada para o aviso aparecer.",
    "Prompt reset" to "Aviso redefinido",
    "Miscellaneous" to "Diversos",
    "Search for a recipient" to "Buscar destinatário",
    "Search by ID, ACI, or PNI." to "Busca por ID, ACI ou PNI.",
    "'Internal Details' button" to "Botão “Detalhes internos”",
    "Show a button in conversation settings that lets you see more information about a user." to "Mostra nas configurações da conversa um botão com mais informações sobre a pessoa.",
    "Shake to Report" to "Agitar para enviar relatório",
    "Shake your phone to easily submit and share a debug log." to "Agite o telefone para enviar e compartilhar um registro de diagnóstico.",
    "Show archive status hint" to "Exibir estado do arquivo",
    "Shows a color square based on archive status, green good, red bad." to "Mostra um quadrado colorido conforme o estado do arquivo: verde para bom e vermelho para ruim.",
    "Log dump PreKey ServiceId-KeyIds" to "Registrar IDs das chaves PreKey",
    "Force prekey rotation" to "Forçar renovação das chaves PreKey",
    "Clears the last forced refresh time and enqueues a forced PreKeysSyncJob, rotating all ACI and PNI prekeys." to "Limpa o último horário de atualização forçada e renova as chaves PreKey de ACI e PNI.",
    "Enqueued forced prekey rotation" to "Renovação forçada das chaves PreKey agendada",
    "Retry all jobs now" to "Tentar novamente todas as tarefas",
    "Clear backoff intervals, app will restart" to "Limpa os intervalos de espera. O app será reiniciado.",
    "Delete all prekeys" to "Excluir todas as chaves PreKey",
    "Deletes all signed/last-resort/one-time prekeys for both ACI and PNI accounts. WILL cause problems." to "Exclui todas as chaves assinadas, de último recurso e de uso único das contas ACI e PNI. Isso VAI causar problemas.",
    "Delete all prekeys?" to "Excluir todas as chaves PreKey?",
    "Are you sure? This will delete all prekeys for both ACI and PNI accounts. This WILL cause problems." to "Tem certeza? Isso excluirá todas as chaves PreKey das contas ACI e PNI e VAI causar problemas.",
    "All prekeys deleted!" to "Todas as chaves PreKey foram excluídas!",
    "Run self-check key transparency" to "Executar verificação de transparência de chaves",
    "Automatically enqueues a job to run KT against yourself without waiting for the elapsed time." to "Agenda uma verificação de transparência de chaves da sua conta sem esperar o intervalo normal.",
    "Run unread reminder job" to "Executar aviso de mensagens não lidas",
    "Generates an unread reminder notification based on unreads and muted preferences. Skips the three day cooldown." to "Gera uma notificação de lembrete conforme as mensagens não lidas e as conversas silenciadas, ignorando o intervalo de três dias.",
    "Enable ANR-induced crashing" to "Ativar encerramento forçado por ANR",
    "Logging" to "Registros",
    "Clear all logs" to "Apagar todos os registros",
    "Cleared all logs" to "Todos os registros foram apagados",
    "Clear keep longer logs" to "Apagar registros mantidos por mais tempo",
    "Clear all crashes" to "Apagar todos os encerramentos inesperados",
    "Cleared crashes" to "Encerramentos inesperados apagados",
    "Clear all ANRs" to "Apagar todos os ANRs",
    "Cleared ANRs" to "ANRs apagados",
    "Clear local metrics" to "Apagar métricas locais",
    "Click to clear all local metrics state." to "Toque para apagar todos os dados de métricas locais.",
    "Payments" to "Pagamentos",
    "Copy payments data" to "Copiar dados de pagamentos",
    "Copy all payment records to clipboard." to "Copia todos os registros de pagamento para a área de transferência.",
    "Storage Service" to "Serviço de armazenamento",
    "Disable syncing" to "Desativar sincronização",
    "Prevent syncing any data to/from storage service." to "Impede a sincronização de dados com o serviço de armazenamento.",
    "Sync now" to "Sincronizar agora",
    "Enqueue a normal storage service sync." to "Agenda uma sincronização normal com o serviço de armazenamento.",
    "Overwrite remote data" to "Substituir dados remotos",
    "Forces remote storage to match the local device state." to "Faz o armazenamento remoto corresponder ao estado deste aparelho.",
    "Groups V2" to "Grupos V2",
    "Force invites" to "Forçar convites",
    "Members will not be added directly to a GV2 even if they could be." to "Os participantes não serão adicionados diretamente a um grupo GV2, mesmo quando isso seria possível.",
    "Ignore P2P changes" to "Ignorar alterações P2P",
    "Changes sent P2P will be ignored. In conjunction with ignoring server changes, will cause passive voice." to "As alterações enviadas P2P serão ignoradas. Junto com a opção de ignorar alterações do servidor, isso causa o modo passivo.",
    "Network" to "Rede",
    "Allow censorship circumvention toggle" to "Permitir controle para contornar bloqueios",
    "Allow changing the censorship circumvention toggle regardless of network connectivity." to "Permite alterar a opção de contorno de bloqueios independentemente da conexão de rede.",
    "Conversations and Shortcuts" to "Conversas e atalhos",
    "Delete all dynamic shortcuts" to "Excluir todos os atalhos dinâmicos",
    "Click to delete all dynamic shortcuts" to "Toque para excluir todos os atalhos dinâmicos.",
    "Emoji" to "Emojis",
    "Use built-in emoji set" to "Usar conjunto de emojis integrado",
    "Force emoji download" to "Forçar download de emojis",
    "Download the latest emoji set if it's newer than what we have." to "Baixa o conjunto de emojis mais recente, se for mais novo que o atual.",
    "Force search index download" to "Forçar download do índice de busca",
    "Download the latest emoji search index if it's newer than what we have." to "Baixa o índice de busca de emojis mais recente, se for mais novo que o atual.",
    "Sender Key" to "Chave do remetente",
    "Clear all state" to "Limpar todos os dados",
    "Click to delete all sender key state" to "Toque para apagar todos os dados da chave do remetente.",
    "Clear shared state" to "Limpar dados compartilhados",
    "Click to delete all sharing state" to "Toque para apagar todos os dados compartilhados.",
    "Remove 2 person minimum" to "Remover mínimo de 2 pessoas",
    "Remove the requirement that you  need at least 2 recipients to use sender key." to "Remove a exigência de pelo menos dois destinatários para usar a chave do remetente.",
    "Delay resends" to "Atrasar reenvios",
    "Delay resending messages in response to retry receipts by 10 seconds." to "Atrasar em 10 segundos o reenvio de mensagens após um aviso de nova tentativa.",
    "Group call server" to "Servidor de chamadas em grupo",
    "Production server" to "Servidor de produção",
    "\$name server" to "Servidor \$name",
    "Calling options" to "Opções de chamada",
    "Display call quality survey" to "Exibir pesquisa sobre qualidade da chamada",
    "Bandwidth mode" to "Modo de largura de banda",
    "Use Telecom integration" to "Usar integração de chamadas do Android",
    "Set Audio Config:" to "Definir configuração de áudio:",
    "    Use Oboe ADM" to "    Usar Oboe ADM",
    "    Use Software AEC" to "    Usar AEC por software",
    "    Use Software NS" to "    Usar redução de ruído por software",
    "    Use Input Low Latency" to "    Usar entrada de baixa latência",
    "    Use Input Voice Comm" to "    Usar entrada de voz",
    "Set Video Config:" to "Definir configuração de vídeo:",
    "    Use Hardware Vp9 Encode" to "    Codificar VP9 por hardware",
    "    Use Hardware Vp9 Decode" to "    Decodificar VP9 por hardware",
    "    Use Software Vp9 Encode" to "    Codificar VP9 por software",
    "    Use Software Vp9 Decode" to "    Decodificar VP9 por software",
    "    Use Hardware H265 Encode" to "    Codificar H.265 por hardware",
    "    Use Hardware H265 Decode" to "    Decodificar H.265 por hardware",
    "Enable SVC" to "Ativar SVC",
    "Stats Interval (secs)" to "Intervalo das estatísticas (s)",
    "Default" to "Padrão",
    "How often RingRTC should report call stats. Leave blank or enter 0 to use the default interval." to "Frequência com que o RingRTC informa as estatísticas da chamada. Deixe em branco ou digite 0 para usar o intervalo padrão.",
    "Minimum Capture FPS" to "FPS mínimo de captura",
    "Floor for the camera's capture framerate range. 0 for default logic. Applies on next call start." to "Limite mínimo da taxa de quadros da câmera. 0 usa o padrão. A alteração vale na próxima chamada.",
    "Badges" to "Distintivos",
    "Enqueue redemption." to "Agendar resgate.",
    "Enqueue keep-alive." to "Agendar manutenção de atividade.",
    "Set error state." to "Definir estado de erro.",
    "Clear keep-alive timestamps" to "Limpar horários de manutenção de atividade",
    "Cleared" to "Limpo",
    "Clear pending one-time donation." to "Limpar doação única pendente.",
    "Set pending one-time donation." to "Definir doação única pendente.",
    "Enqueue terminal donation" to "Agendar doação final",
    "Release channel" to "Canal de versão",
    "Set last version seen back 10 versions" to "Voltar 10 versões no histórico visto",
    "Reset donation megaphone" to "Redefinir aviso de doação",
    "Fetch release channel" to "Buscar canal de versão",
    "Add sample note" to "Adicionar nota de exemplo",
    "Add remote backups note" to "Adicionar nota sobre backups remotos",
    "Add remote donate megaphone" to "Adicionar aviso remoto de doação",
    "Add donate_friend remote megaphone" to "Adicionar aviso remoto “doar a um amigo”",
    "CDS" to "CDS",
    "Clear history" to "Apagar histórico",
    "Clears all CDS history, meaning the next sync will consider all numbers to be new." to "Apaga todo o histórico CDS; na próxima sincronização, todos os números serão considerados novos.",
    "Clear all service IDs" to "Apagar todos os IDs de serviço",
    "Clears all known service IDs (except your own) for people that have phone numbers. Do not use on your personal device!" to "Apaga todos os IDs de serviço conhecidos, exceto o seu, de pessoas com número de telefone. Não use no seu aparelho pessoal!",
    "Clear all profile keys" to "Apagar todas as chaves de perfil",
    "Clears all known profile keys (except your own). Do not use on your personal device!" to "Apaga todas as chaves de perfil conhecidas, exceto as suas. Não use no seu aparelho pessoal!",
    "Stories" to "Stories",
    "Clear onboarding state" to "Limpar estado de apresentação inicial",
    "Clears onboarding flag and triggers download of onboarding stories." to "Limpa a indicação de apresentação inicial e inicia o download dos Stories correspondentes.",
    "Clear choose initial my story privacy state" to "Limpar escolha inicial de privacidade dos meus Stories",
    "Clear first time navigation state" to "Limpar estado da navegação inicial",
    "Stories dialog launcher" to "Atalho da janela de Stories",
    "PNP" to "PNP",
    "Reset 'PNP initialized' state" to "Redefinir estado “PNP inicializado”",
    "Corrupt username" to "Distorcer nome de usuário",
    "Changes our local username without telling the server so it falls out of sync. Refresh profile afterwards to trigger corruption." to "Altera o nome de usuário neste aparelho sem avisar o servidor, causando divergência. Atualize o perfil depois para acionar o teste.",
    "Corrupt your username?" to "Distorcer seu nome de usuário?",
    "Are you sure? You might not be able to get your original username back." to "Tem certeza? Talvez não seja possível recuperar o nome de usuário original.",
    "Done" to "Concluído",
    "Corrupt username link" to "Distorcer link do nome de usuário",
    "Changes our local username link without telling the server so it falls out of sync. Refresh profile afterwards to trigger corruption." to "Altera o link do nome de usuário neste aparelho sem avisar o servidor, causando divergência. Atualize o perfil depois para acionar o teste.",
    "Corrupt your username link?" to "Distorcer o link do seu nome de usuário?",
    "Are you sure? You'll have to reset your link." to "Tem certeza? Você terá que redefinir o link.",
    "Chat Filters" to "Filtros de conversa",
    "Reset pull to refresh tip count" to "Redefinir contador da dica de atualização",
    "Launch Conversation Test Springboard " to "Abrir painel de testes de conversa",
    "Use V2 ConversationItem for Media" to "Usar item de conversa V2 para mídia",
    "Unregister?" to "Cancelar cadastro?",
    "Are you sure? You'll have to re-register to use Signal again -- no promises that the process will go smoothly." to "Tem certeza? Você precisará fazer o cadastro novamente para usar o Signal. Não garantimos que o processo será concluído sem problemas.",
    "Unregistered!" to "Cadastro cancelado!",
    "Network error!" to "Erro de rede!",
    "No service password set!" to "Nenhuma senha de serviço definida!",
    "Copy service password?" to "Copiar senha do serviço?",
    "Your service password lets anyone who has it send messages as you on the service. Treat it like a password: don't paste it anywhere you don't fully trust. It will be cleared from the clipboard after \${Util.SENSITIVE_CLIPBOARD_TIMEOUT_SECONDS} seconds." to "Sua senha de serviço permite que qualquer pessoa que a tenha envie mensagens em seu nome. Trate-a como uma senha: não a cole em locais que não sejam confiáveis. Ela será removida da área de transferência após \${Util.SENSITIVE_CLIPBOARD_TIMEOUT_SECONDS} segundos.",
    "Copied service password" to "Senha do serviço copiada",
    "Copy" to "Copiar",
    "Payments have been copied" to "Dados de pagamentos copiados",
    "Scheduled attribute refresh" to "Atualização de atributos agendada",
    "Scheduled profile refresh" to "Atualização do perfil agendada",
    "Scheduled profile key rotation" to "Renovação da chave do perfil agendada",
    "Running remote config refresh, app will restart after completion." to "Atualizando a configuração remota. O app será reiniciado ao concluir.",
    "Refresh successful. Restarting..." to "Atualização concluída. Reiniciando…",
    "Failed to refresh config remote config." to "Falha ao atualizar a configuração remota.",
    "Scheduled routine storage sync" to "Sincronização normal do armazenamento agendada",
    "Scheduled storage force push" to "Envio forçado ao armazenamento agendado",
    "Deleted all dynamic shortcuts." to "Todos os atalhos dinâmicos foram excluídos.",
    "Deleted all sender key state." to "Todos os dados da chave do remetente foram apagados.",
    "Deleted all sender key shared state." to "Todos os dados compartilhados da chave do remetente foram apagados.",
    "Cleared all local metrics state." to "Todos os dados de métricas locais foram apagados.",
    "Cleared all CDS history." to "Todo o histórico CDS foi apagado.",
    "Clear all serviceIds?" to "Apagar todos os IDs de serviço?",
    "Are you sure? Never do this on a non-test device." to "Tem certeza? Nunca faça isso em um aparelho que não seja de teste.",
    "Cleared all service IDs." to "Todos os IDs de serviço foram apagados.",
    "Clear all profile keys?" to "Apagar todas as chaves de perfil?",
    "Cleared all profile keys." to "Todas as chaves de perfil foram apagadas.",
    "Cleared keep longer logs" to "Registros mantidos por mais tempo apagados",
    "Dumped to logs" to "Dados registrados",
    "Export quickstart credentials?" to "Exportar credenciais de início rápido?",
    "This will export your account's private keys and credentials to an unencrypted file on disk. This is very dangerous! Only use it with test accounts." to "Isso exportará as chaves privadas e credenciais da conta para um arquivo sem criptografia. É muito perigoso! Use somente com contas de teste.",
    "Export" to "Exportar",
    "Exported to \${file.absolutePath}" to "Exportado para \${file.absolutePath}",
    "Failed to parse number!" to "Não foi possível interpretar o número!",
    "Enter sentTimestamp" to "Digite o horário de envio",
    "Failed to parse timestamp!" to "Não foi possível interpretar o horário!",
    "Could not find a message with that timestamp!" to "Não foi encontrada mensagem com esse horário!",
    "There's \${messages.size} messages with that timestamp! Go run SQL or something." to "Há \${messages.size} mensagens com esse horário. Consulte o banco de dados para diferenciá-las."
    )

}
