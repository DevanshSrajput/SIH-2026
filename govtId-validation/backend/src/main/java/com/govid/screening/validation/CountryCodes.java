package com.govid.screening.validation;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Country and nationality codes accepted in a travel document.
 *
 * <p>A code outside this set means the field was mis-read or fabricated. Forged documents
 * frequently carry a plausible-looking but non-existent issuing state, so this is a cheap,
 * high-yield check.
 */
public final class CountryCodes {

    /** ISO 3166-1 alpha-3. */
    private static final String ISO_3166_ALPHA3 = """
            ABW AFG AGO AIA ALA ALB AND ARE ARG ARM ASM ATA ATF ATG AUS AUT AZE
            BDI BEL BEN BES BFA BGD BGR BHR BHS BIH BLM BLR BLZ BMU BOL BRA BRB BRN BTN BVT BWA
            CAF CAN CCK CHE CHL CHN CIV CMR COD COG COK COL COM CPV CRI CUB CUW CXR CYM CYP CZE
            DEU DJI DMA DNK DOM DZA ECU EGY ERI ESH ESP EST ETH
            FIN FJI FLK FRA FRO FSM GAB GBR GEO GGY GHA GIB GIN GLP GMB GNB GNQ GRC GRD GRL GTM GUF GUM GUY
            HKG HMD HND HRV HTI HUN IDN IMN IND IOT IRL IRN IRQ ISL ISR ITA
            JAM JEY JOR JPN KAZ KEN KGZ KHM KIR KNA KOR KWT
            LAO LBN LBR LBY LCA LIE LKA LSO LTU LUX LVA
            MAC MAF MAR MCO MDA MDG MDV MEX MHL MKD MLI MLT MMR MNE MNG MNP MOZ MRT MSR MTQ MUS MWI MYS MYT
            NAM NCL NER NFK NGA NIC NIU NLD NOR NPL NRU NZL OMN
            PAK PAN PCN PER PHL PLW PNG POL PRI PRK PRT PRY PSE PYF QAT REU ROU RUS RWA
            SAU SDN SEN SGP SGS SHN SJM SLB SLE SLV SMR SOM SPM SRB SSD STP SUR SVK SVN SWE SWZ SXM SYC SYR
            TCA TCD TGO THA TJK TKL TKM TLS TON TTO TUN TUR TUV TWN TZA
            UGA UKR UMI URY USA UZB VAT VCT VEN VGB VIR VNM VUT WLF WSM YEM ZAF ZMB ZWE
            """;

    /**
     * Codes that are valid in an MRZ but are not ISO country codes.
     *
     * <p>{@code D} is Germany's MRZ code, {@code XX*} cover stateless persons and
     * refugees under the 1951 Convention, {@code GB*} are the non-citizen British
     * nationality classes, and {@code UN*} are United Nations laissez-passer.
     */
    private static final String ICAO_SPECIAL = """
            D XXA XXB XXC XXX
            GBD GBN GBO GBP GBS
            UNO UNA UNK EUE RKS
            """;

    private static final Set<String> VALID = build();

    private CountryCodes() {
    }

    private static Set<String> build() {
        Set<String> codes = new LinkedHashSet<>();
        Arrays.stream((ISO_3166_ALPHA3 + " " + ICAO_SPECIAL).split("\\s+"))
                .map(String::trim)
                .filter(code -> !code.isEmpty())
                .forEach(codes::add);
        return Set.copyOf(codes);
    }

    /** True when the code is a recognised issuing state or nationality. */
    public static boolean isValid(String code) {
        if (code == null) {
            return false;
        }
        return VALID.contains(code.trim().toUpperCase());
    }

    public static int size() {
        return VALID.size();
    }
}
