package com.example.uhf.accounting;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.uhf.fragment.MiraAccountTreeFragment;
import com.example.uhf.fragment.MiraAdjustingEntryFragment;
import com.example.uhf.fragment.MiraAgingReportFragment;
import com.example.uhf.fragment.MiraBalanceSheetFragment;
import com.example.uhf.fragment.MiraCashReconciliationFragment;
import com.example.uhf.fragment.MiraChequeFragment;
import com.example.uhf.fragment.MiraFiscalClosingFragment;
import com.example.uhf.fragment.MiraFixedAssetsFragment;
import com.example.uhf.fragment.MiraGeneralLedgerFragment;
import com.example.uhf.fragment.MiraGoldMovementFragment;
import com.example.uhf.fragment.MiraIncomeStatementFragment;
import com.example.uhf.fragment.MiraInventoryRevaluationFragment;
import com.example.uhf.fragment.MiraJournalFragment;
import com.example.uhf.fragment.MiraPeriodLockFragment;
import com.example.uhf.fragment.MiraProfitAnalysisFragment;
import com.example.uhf.fragment.MiraReconciliationFragment;
import com.example.uhf.fragment.MiraTrialBalanceFragment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MIRA Accounting — Central Screen Registry
 *
 * المصدر الوحيد للحقيقة لجميع شاشات نظام المحاسبة.
 * كل شاشة معرّفة هنا مرة واحدة فقط: الاسم، الأيقونة، القسم، الصلاحية، وكيف تُنشأ.
 *
 * الفوائد:
 *  1. تغيير ترتيب الشاشات لا يكسر أي كود آخر
 *  2. إضافة شاشة = سطر واحد هنا فقط
 *  3. استرجاع الشاشة بالمعرّف الثابت (id) أو بالتبويب القديم (legacyTab)
 *  4. الصلاحيات مركزية بدل تكرارها في كل مكان
 *  5. أنواع الشاشات معروفة في وقت الترجمة، لا hardcoded في switch cases
 *
 * للتوافق الخلفي: كل شاشة تحمل رقم التبويب الذي كانت تشغله في
 * MiraAccountingFragment قبل إعادة التصميم. أي كود قديم يستدعي
 * navigateToTab(int) سيظل يعمل عبر byLegacyTab().
 *
 * @version 1.0.0
 * @since 2026-10
 */
public final class MiraAccountingScreens {

    // ═══════════════════════════════════════════════════════════
    //  معرّفات الشاشات — عقد ثابت لا يتغير
    //  لا تعتمد على اسم الثابت في التخزين (SharedPreferences،
    //  Intent extras، إلخ) — استخدم القيمة النصية نفسها.
    // ═══════════════════════════════════════════════════════════
    public static final String ID_DASHBOARD             = "dashboard";
    public static final String ID_JOURNAL               = "journal";
    public static final String ID_TRIAL_BALANCE         = "trial_balance";
    public static final String ID_ACCOUNTS_TREE         = "accounts_tree";
    public static final String ID_INCOME_STATEMENT      = "income_statement";
    public static final String ID_BALANCE_SHEET         = "balance_sheet";
    public static final String ID_GOLD_MOVEMENT         = "gold_movement";
    public static final String ID_GENERAL_LEDGER        = "general_ledger";
    public static final String ID_ADJUSTING_ENTRY       = "adjusting_entry";
    public static final String ID_CASH_RECONCILIATION   = "cash_reconciliation";
    public static final String ID_AGING                 = "aging";
    public static final String ID_FISCAL_CLOSING        = "fiscal_closing";
    public static final String ID_PROFIT_ANALYSIS       = "profit_analysis";
    public static final String ID_INVENTORY_REVALUATION = "inventory_revaluation";
    public static final String ID_PERIOD_LOCK           = "period_lock";
    public static final String ID_CHEQUES               = "cheques";
    public static final String ID_BANK_RECONCILIATION   = "bank_reconciliation";
    public static final String ID_FIXED_ASSETS          = "fixed_assets";

    /**
     * الصلاحية الأساسية للمحاسبة — تحافظ على سلوك PermissionManager الحالي.
     * أي شاشة لا تحمل صلاحية خاصة تُورَث هذه القيمة.
     */
    public static final String BASE_PERMISSION = "accounting";

    // ═══════════════════════════════════════════════════════════
    //  الأقسام الرئيسية
    // ═══════════════════════════════════════════════════════════
    public enum Section {
        HOME       ("🏠", "الرئيسية",            "Home"),
        OPERATIONS ("📋", "العمليات المحاسبية", "Accounting Operations"),
        REPORTS    ("📊", "التقارير المالية",   "Financial Reports"),
        CASH       ("💰", "النقدية والبنوك",     "Cash & Banking"),
        GOLD       ("🥇", "الذهب والمخزون",      "Gold & Inventory"),
        CONTROLS   ("🔒", "الرقابة والفترات",   "Controls & Periods"),
        ASSETS     ("🏢", "الأصول",              "Assets");

        public final String icon;
        public final String titleAr;
        public final String titleEn;

        Section(String icon, String titleAr, String titleEn) {
            this.icon = icon;
            this.titleAr = titleAr;
            this.titleEn = titleEn;
        }

        public String title(boolean isArabic) {
            return isArabic ? titleAr : titleEn;
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  مصنع الشاشات
    //  واجهة بسيطة بدل Supplier لجعل الملف يعمل على أي minSdk
    //  بدون الحاجة لتفعيل core library desugaring.
    // ═══════════════════════════════════════════════════════════
    public interface FragmentFactory {
        @NonNull Fragment create();
    }

    // ═══════════════════════════════════════════════════════════
    //  تعريف الشاشة — بيانات وصفية فقط، لا حالة
    // ═══════════════════════════════════════════════════════════
    public static final class Screen {

        /** معرّف ثابت لا يتغير أبداً */
        public final String id;

        /** رقم التبويب في التصميم القديم (-1 = شاشة جديدة لم تكن تبويباً) */
        public final int legacyTab;

        /** العنوان — عربي */
        public final String titleAr;

        /** العنوان — إنجليزي */
        public final String titleEn;

        /** أيقونة (emoji) */
        public final String icon;

        /** القسم الذي تنتمي إليه */
        public final Section section;

        /**
         * صلاحية خاصة بالشاشة (مستقبلاً: "accounting.journal.view").
         * القيمة الافتراضية = BASE_PERMISSION (لا تفرقة اليوم).
         */
        public final String permissionKey;

        /** دالة إنشاء الشاشة — تُنفَّذ عند أول طلب فقط */
        public final FragmentFactory factory;

        public Screen(String id,
                      int legacyTab,
                      String titleAr,
                      String titleEn,
                      String icon,
                      Section section,
                      String permissionKey,
                      FragmentFactory factory) {

            if (id == null || id.isEmpty())
                throw new IllegalArgumentException("Screen id required");
            if (section == null)
                throw new IllegalArgumentException("Screen section required: " + id);
            if (factory == null)
                throw new IllegalArgumentException("Screen factory required: " + id);

            this.id = id;
            this.legacyTab = legacyTab;
            this.titleAr = titleAr != null ? titleAr : id;
            this.titleEn = titleEn != null ? titleEn : id;
            this.icon = icon != null ? icon : "•";
            this.section = section;
            this.permissionKey = permissionKey != null ? permissionKey : BASE_PERMISSION;
            this.factory = factory;
        }

        public String title(boolean isArabic) {
            return isArabic ? titleAr : titleEn;
        }

        /** هل هذه الشاشة كانت تبويباً في التصميم القديم؟ */
        public boolean isLegacyTab() {
            return legacyTab >= 0;
        }

        @Override
        public String toString() {
            return "Screen{" + id + ", tab=" + legacyTab + ", section=" + section + "}";
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  السجل — ترتيب الشاشات هنا = ترتيب ظهورها في القائمة
    // ═══════════════════════════════════════════════════════════
    private static final List<Screen> REGISTRY;

    static {
        List<Screen> list = new ArrayList<>(18);

        // ─── HOME ──────────────────────────────────────────────
        list.add(new Screen(ID_DASHBOARD, -1,
                "لوحة التحكم", "Dashboard", "🏠",
                Section.HOME, BASE_PERMISSION,
                MiraAccountingDashboardFragment::new));

        // ─── OPERATIONS ────────────────────────────────────────
        list.add(new Screen(ID_JOURNAL, 0,
                "القيود", "Journal", "📋",
                Section.OPERATIONS, BASE_PERMISSION,
                MiraJournalFragment::new));

        list.add(new Screen(ID_ACCOUNTS_TREE, 2,
                "شجرة الحسابات", "Chart of Accounts", "📊",
                Section.OPERATIONS, BASE_PERMISSION,
                MiraAccountTreeFragment::new));

        list.add(new Screen(ID_GENERAL_LEDGER, 6,
                "دفتر الأستاذ", "General Ledger", "📖",
                Section.OPERATIONS, BASE_PERMISSION,
                MiraGeneralLedgerFragment::new));

        list.add(new Screen(ID_ADJUSTING_ENTRY, 7,
                "التسويات", "Adjusting Entries", "📝",
                Section.OPERATIONS, BASE_PERMISSION,
                MiraAdjustingEntryFragment::new));

        // ─── REPORTS ───────────────────────────────────────────
        list.add(new Screen(ID_TRIAL_BALANCE, 1,
                "ميزان المراجعة", "Trial Balance", "⚖️",
                Section.REPORTS, BASE_PERMISSION,
                MiraTrialBalanceFragment::new));

        list.add(new Screen(ID_INCOME_STATEMENT, 3,
                "قائمة الدخل", "Income Statement", "📈",
                Section.REPORTS, BASE_PERMISSION,
                MiraIncomeStatementFragment::new));

        list.add(new Screen(ID_BALANCE_SHEET, 4,
                "الميزانية", "Balance Sheet", "💰",
                Section.REPORTS, BASE_PERMISSION,
                MiraBalanceSheetFragment::new));

        list.add(new Screen(ID_PROFIT_ANALYSIS, 11,
                "تحليل الأرباح", "Profit Analysis", "📊",
                Section.REPORTS, BASE_PERMISSION,
                MiraProfitAnalysisFragment::new));

        // ─── CASH ──────────────────────────────────────────────
        list.add(new Screen(ID_CASH_RECONCILIATION, 8,
                "الخزينة", "Cash", "💵",
                Section.CASH, BASE_PERMISSION,
                MiraCashReconciliationFragment::new));

        list.add(new Screen(ID_CHEQUES, 14,
                "الشيكات", "Cheques", "📑",
                Section.CASH, BASE_PERMISSION,
                MiraChequeFragment::new));

        list.add(new Screen(ID_BANK_RECONCILIATION, 15,
                "المطابقة البنكية", "Bank Reconciliation", "🏦",
                Section.CASH, BASE_PERMISSION,
                MiraReconciliationFragment::new));

        // ─── GOLD ──────────────────────────────────────────────
        list.add(new Screen(ID_GOLD_MOVEMENT, 5,
                "حركة الذهب", "Gold Movement", "🥇",
                Section.GOLD, BASE_PERMISSION,
                MiraGoldMovementFragment::new));

        list.add(new Screen(ID_INVENTORY_REVALUATION, 12,
                "إعادة التقييم", "Revaluation", "⚖️",
                Section.GOLD, BASE_PERMISSION,
                MiraInventoryRevaluationFragment::new));

        // ─── CONTROLS ──────────────────────────────────────────
        list.add(new Screen(ID_AGING, 9,
                "تقادم الديون", "Aging", "⏳",
                Section.CONTROLS, BASE_PERMISSION,
                MiraAgingReportFragment::new));

        list.add(new Screen(ID_FISCAL_CLOSING, 10,
                "الإقفال المالي", "Fiscal Closing", "🔒",
                Section.CONTROLS, BASE_PERMISSION,
                MiraFiscalClosingFragment::new));

        list.add(new Screen(ID_PERIOD_LOCK, 13,
                "الفترات المحاسبية", "Period Lock", "🔐",
                Section.CONTROLS, BASE_PERMISSION,
                MiraPeriodLockFragment::new));

        // ─── ASSETS ────────────────────────────────────────────
        list.add(new Screen(ID_FIXED_ASSETS, 16,
                "الأصول الثابتة", "Fixed Assets", "🏢",
                Section.ASSETS, BASE_PERMISSION,
                MiraFixedAssetsFragment::new));

        REGISTRY = Collections.unmodifiableList(list);
    }

    // ═══════════════════════════════════════════════════════════
    //  فهارس للبحث السريع O(1)
    // ═══════════════════════════════════════════════════════════
    private static final Map<String, Screen> BY_ID;
    private static final Map<Integer, Screen> BY_LEGACY_TAB;
    private static final Map<Section, List<Screen>> BY_SECTION;

    static {
        Map<String, Screen> byId = new HashMap<>(REGISTRY.size());
        Map<Integer, Screen> byTab = new HashMap<>(REGISTRY.size());
        Map<Section, List<Screen>> bySection = new LinkedHashMap<>();

        for (Screen s : REGISTRY) {
            byId.put(s.id, s);
            if (s.legacyTab >= 0) byTab.put(s.legacyTab, s);
            bySection.computeIfAbsent(s.section, k -> new ArrayList<>()).add(s);
        }

        BY_ID = Collections.unmodifiableMap(byId);
        BY_LEGACY_TAB = Collections.unmodifiableMap(byTab);

        Map<Section, List<Screen>> frozen = new LinkedHashMap<>();
        for (Map.Entry<Section, List<Screen>> e : bySection.entrySet()) {
            frozen.put(e.getKey(), Collections.unmodifiableList(e.getValue()));
        }
        BY_SECTION = Collections.unmodifiableMap(frozen);
    }

    // ═══════════════════════════════════════════════════════════
    //  واجهة الاستعلام العامة
    // ═══════════════════════════════════════════════════════════

    /** كل الشاشات بترتيب السجل */
    @NonNull
    public static List<Screen> all() {
        return REGISTRY;
    }

    /** جلب شاشة بالمعرّف. @return null إن لم يوجد */
    @Nullable
    public static Screen byId(@Nullable String id) {
        return id == null ? null : BY_ID.get(id);
    }

    /**
     * جلب شاشة برقم التبويب القديم — للتوافق الخلفي مع navigateToTab(int).
     * @return null إن لم يوجد تبويب بهذا الرقم
     */
    @Nullable
    public static Screen byLegacyTab(int tab) {
        return BY_LEGACY_TAB.get(tab);
    }

    /** شاشات قسم معين بترتيب السجل */
    @NonNull
    public static List<Screen> inSection(@NonNull Section section) {
        List<Screen> list = BY_SECTION.get(section);
        return list != null ? list : Collections.emptyList();
    }

    /** كل الأقسام بترتيب الـ enum */
    @NonNull
    public static List<Section> allSections() {
        return Arrays.asList(Section.values());
    }

    /**
     * الشاشة الافتراضية عند فتح المحاسبة لأول مرة.
     * حاليًا = لوحة التحكم. لو أردت إرجاع السلوك القديم (القيود أولاً)،
     * غيّر هذه القيمة فقط — لا حاجة لتعديل أي كود آخر.
     */
    @NonNull
    public static String defaultScreenId() {
        return ID_DASHBOARD;
    }

    /** عدد الشاشات المسجّلة — للاختبار */
    public static int size() {
        return REGISTRY.size();
    }
}
