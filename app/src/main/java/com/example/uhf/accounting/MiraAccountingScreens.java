package com.example.uhf.accounting;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.uhf.R;
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
// تأكد من وجود هذا الكلاس، لقد قمت بإضافته بناءً على الكود السابق
import com.example.uhf.accounting.MiraAccountingDashboardFragment; 

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MIRA Accounting — Central Screen Registry (Enterprise v2.0)
 * 
 * التحديثات في هذا الإصدار:
 * 1. استخدام أيقونات حقيقية (Vector Drawables) بدلاً من الإيموجي.
 * 2. إضافة صلاحيات دقيقة (Granular Permissions) لكل شاشة على حدة.
 * 3. تحسين هيكلة الأقسام لتكون قابلة للفلترة لاحقاً.
 */
public final class MiraAccountingScreens {

    // ═══════════════════════════════════════════════════════════
    //  معرّفات الشاشات — عقد ثابت لا يتغير
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
     * الصلاحية الأساسية للمحاسبة — تُستخدم كـ Fallback
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
    // ═══════════════════════════════════════════════════════════
    public interface FragmentFactory {
        @NonNull Fragment create();
    }

    // ═══════════════════════════════════════════════════════════
    //  تعريف الشاشة
    // ═══════════════════════════════════════════════════════════
    public static final class Screen {

        public final String id;
        public final int legacyTab;
        public final String titleAr;
        public final String titleEn;
        
        /** ✅ معرف الأيقونة الحقيقية (Vector Drawable) */
        @DrawableRes 
        public final int iconRes;
        
        public final Section section;
        public final String permissionKey;
        public final FragmentFactory factory;

        public Screen(String id, int legacyTab, String titleAr, String titleEn,
                      @DrawableRes int iconRes, Section section, String permissionKey,
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
            this.iconRes = iconRes;
            this.section = section;
            this.permissionKey = permissionKey != null ? permissionKey : BASE_PERMISSION;
            this.factory = factory;
        }

        public String title(boolean isArabic) {
            return isArabic ? titleAr : titleEn;
        }

        public boolean isLegacyTab() {
            return legacyTab >= 0;
        }

        @Override
        public String toString() {
            return "Screen{" + id + ", tab=" + legacyTab + ", section=" + section + "}";
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  السجل — تم تحديث الأيقونات والصلاحيات
    // ═══════════════════════════════════════════════════════════
    private static final List<Screen> REGISTRY;

    static {
        List<Screen> list = new ArrayList<>(18);

        // ─── HOME ──────────────────────────────────────────────
        // ملاحظة: تأكد من أن الأيقونات (R.drawable.ic_...) موجودة في مجلد drawable.
        // إذا لم تكن موجودة، يمكنك استخدام android.R.drawable.ic_menu_... مؤقتاً.
        
        list.add(new Screen(ID_DASHBOARD, -1, "لوحة التحكم", "Dashboard", 
                R.drawable.ic_home, Section.HOME, BASE_PERMISSION,
                MiraAccountingDashboardFragment::new));

        // ─── OPERATIONS ────────────────────────────────────────
        list.add(new Screen(ID_JOURNAL, 0, "القيود", "Journal", 
                R.drawable.ic_accounting, Section.OPERATIONS, "accounting.journal",
                MiraJournalFragment::new));

        list.add(new Screen(ID_ACCOUNTS_TREE, 2, "شجرة الحسابات", "Chart of Accounts", 
                R.drawable.ic_reports, Section.OPERATIONS, "accounting.chart",
                MiraAccountTreeFragment::new));

        list.add(new Screen(ID_GENERAL_LEDGER, 6, "دفتر الأستاذ", "General Ledger", 
                R.drawable.ic_reports, Section.OPERATIONS, "accounting.ledger",
                MiraGeneralLedgerFragment::new));

        list.add(new Screen(ID_ADJUSTING_ENTRY, 7, "التسويات", "Adjusting Entries", 
                R.drawable.ic_accounting, Section.OPERATIONS, "accounting.adjusting",
                MiraAdjustingEntryFragment::new));

        // ─── REPORTS ───────────────────────────────────────────
        list.add(new Screen(ID_TRIAL_BALANCE, 1, "ميزان المراجعة", "Trial Balance", 
                R.drawable.ic_reports, Section.REPORTS, "accounting.reports",
                MiraTrialBalanceFragment::new));

        list.add(new Screen(ID_INCOME_STATEMENT, 3, "قائمة الدخل", "Income Statement", 
                R.drawable.ic_reports, Section.REPORTS, "accounting.reports",
                MiraIncomeStatementFragment::new));

        list.add(new Screen(ID_BALANCE_SHEET, 4, "الميزانية", "Balance Sheet", 
                R.drawable.ic_reports, Section.REPORTS, "accounting.reports",
                MiraBalanceSheetFragment::new));

        list.add(new Screen(ID_PROFIT_ANALYSIS, 11, "تحليل الأرباح", "Profit Analysis", 
                R.drawable.ic_reports, Section.REPORTS, "accounting.reports",
                MiraProfitAnalysisFragment::new));

        // ─── CASH ──────────────────────────────────────────────
        list.add(new Screen(ID_CASH_RECONCILIATION, 8, "الخزينة", "Cash", 
                R.drawable.ic_accounting, Section.CASH, "accounting.cash",
                MiraCashReconciliationFragment::new));

        list.add(new Screen(ID_CHEQUES, 14, "الشيكات", "Cheques", 
                R.drawable.ic_reports, Section.CASH, "accounting.cheques",
                MiraChequeFragment::new));

        list.add(new Screen(ID_BANK_RECONCILIATION, 15, "المطابقة البنكية", "Bank Reconciliation", 
                R.drawable.ic_accounting, Section.CASH, "accounting.reconciliation",
                MiraReconciliationFragment::new));

        // ─── GOLD ──────────────────────────────────────────────
        list.add(new Screen(ID_GOLD_MOVEMENT, 5, "حركة الذهب", "Gold Movement", 
                R.drawable.ic_gold_scrap, Section.GOLD, "accounting.gold",
                MiraGoldMovementFragment::new));

        list.add(new Screen(ID_INVENTORY_REVALUATION, 12, "إعادة التقييم", "Revaluation", 
                R.drawable.ic_gold_scrap, Section.GOLD, "accounting.revaluation",
                MiraInventoryRevaluationFragment::new));

        // ─── CONTROLS ──────────────────────────────────────────
        list.add(new Screen(ID_AGING, 9, "تقادم الديون", "Aging", 
                R.drawable.ic_reports, Section.CONTROLS, "accounting.aging",
                MiraAgingReportFragment::new));

        list.add(new Screen(ID_FISCAL_CLOSING, 10, "الإقفال المالي", "Fiscal Closing", 
                R.drawable.ic_lock, Section.CONTROLS, "accounting.fiscal_close",
                MiraFiscalClosingFragment::new));

        list.add(new Screen(ID_PERIOD_LOCK, 13, "الفترات المحاسبية", "Period Lock", 
                R.drawable.ic_lock, Section.CONTROLS, "accounting.periods",
                MiraPeriodLockFragment::new));

        // ─── ASSETS ────────────────────────────────────────────
        list.add(new Screen(ID_FIXED_ASSETS, 16, "الأصول الثابتة", "Fixed Assets", 
                R.drawable.ic_inventory_items, Section.ASSETS, "accounting.assets",
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

    @NonNull
    public static List<Screen> all() {
        return REGISTRY;
    }

    @Nullable
    public static Screen byId(@Nullable String id) {
        return id == null ? null : BY_ID.get(id);
    }

    @Nullable
    public static Screen byLegacyTab(int tab) {
        return BY_LEGACY_TAB.get(tab);
    }

    @NonNull
    public static List<Screen> inSection(@NonNull Section section) {
        List<Screen> list = BY_SECTION.get(section);
        return list != null ? list : Collections.emptyList();
    }

    @NonNull
    public static List<Section> allSections() {
        return Arrays.asList(Section.values());
    }

    @NonNull
    public static String defaultScreenId() {
        return ID_DASHBOARD;
    }

    public static int size() {
        return REGISTRY.size();
    }
}
