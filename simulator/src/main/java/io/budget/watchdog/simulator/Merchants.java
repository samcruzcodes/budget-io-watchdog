package io.budget.watchdog.simulator;

import java.util.List;

/**
 * The simulated world's merchants. Everyday spending uses real brand names so the data reads naturally; fraud uses
 * fictional names so no real company is portrayed as fraudulent. Prices are illustrative.
 */
final class Merchants {

    record Offer(String merchant, long priceCents) {}

    record UtilityAccount(String merchant, long typicalCents, boolean varies) {}

    record Destination(String country, String hotel, String restaurant, String transit, String shop) {}

    record ForeignShop(String merchant, String country) {}

    static final List<String> EMPLOYERS = List.of(
            "Northwind Payroll", "Contoso Payroll", "Fabrikam Payroll", "Globex Payroll", "Initech Payroll",
            "Campus Bookstore Payroll");
    static final String RETIREMENT_PAYER = "SSA Treasury Deposit";
    static final String LANDLORD = "Parkview Apartments";
    static final String MORTGAGE_LENDER = "Summit Home Loans";

    static final List<UtilityAccount> UTILITIES = List.of(
            new UtilityAccount("City Power & Light", 8_500, true),   // electricity: a different amount every month
            new UtilityAccount("City Water Utility", 4_000, true),
            new UtilityAccount("Comcast Xfinity", 7_000, false),     // internet: the same amount every month
            new UtilityAccount("Verizon Wireless", 6_500, false));

    static final List<String> GROCERY_STORES = List.of("Whole Foods Market", "Trader Joe's", "Safeway", "Costco", "Kroger");
    static final List<String> RESTAURANTS = List.of(
            "Chipotle", "Sweetgreen", "Shake Shack", "Panera Bread", "Olive Garden", "Joe's Pizza", "Thai Basil",
            "Sushi Zen", "DoorDash", "Uber Eats");
    static final List<String> COFFEE_SHOPS = List.of("Starbucks", "Blue Bottle Coffee", "Dunkin'", "Peet's Coffee");
    static final List<String> GAS_STATIONS = List.of("Shell", "Chevron", "ExxonMobil");
    static final List<String> RIDESHARE = List.of("Uber", "Lyft");
    static final List<String> SHOPS = List.of("Amazon", "Target", "Walmart", "Etsy", "Nike", "Best Buy", "IKEA");

    static final List<Offer> SUBSCRIPTIONS = List.of(
            new Offer("Netflix", 1_549), new Offer("Spotify", 1_199), new Offer("Hulu", 999),
            new Offer("Disney+", 1_399), new Offer("YouTube Premium", 1_399), new Offer("Apple iCloud+", 299),
            new Offer("Amazon Prime", 1_499), new Offer("Planet Fitness", 2_499), new Offer("Audible", 1_495),
            new Offer("Xbox Game Pass", 1_699), new Offer("The New York Times", 1_700), new Offer("Headspace", 1_299),
            new Offer("Duolingo", 1_299), new Offer("Adobe Creative Cloud", 5_999));
    static final List<Offer> TRIAL_OFFERS = List.of(
            new Offer("Paramount+", 1_199), new Offer("Peacock", 1_099), new Offer("MasterClass", 1_500),
            new Offer("Calm", 1_499), new Offer("Skillshare", 1_399), new Offer("HelloFresh", 6_999),
            new Offer("Max", 1_699));
    static final String APP_STORE = "Apple App Store";
    static final List<Offer> BIG_TICKET_ITEMS = List.of(
            new Offer("Apple Store", 129_900), new Offer("Best Buy", 89_900), new Offer("IKEA", 115_000),
            new Offer("REI", 74_900));

    static final List<String> AIRLINES = List.of("Delta Air Lines", "United Airlines", "American Airlines", "Southwest Airlines");
    static final List<Destination> DESTINATIONS = List.of(
            new Destination("GB", "Premier Inn London", "Dishoom", "Transport for London", "Marks & Spencer"),
            new Destination("FR", "Hotel Le Marais", "Cafe de Flore", "RATP Paris", "Galeries Lafayette"),
            new Destination("IT", "Hotel Artemide Roma", "Roscioli", "ATAC Roma", "La Rinascente"),
            new Destination("ES", "Hotel Casa Fuster", "Bar Canete", "TMB Barcelona", "El Corte Ingles"),
            new Destination("JP", "Hotel Gracery Shinjuku", "Ichiran Ramen", "JR East Suica", "Uniqlo Ginza"),
            new Destination("MX", "Hotel Condesa DF", "El Califa", "Metro CDMX", "El Palacio de Hierro"),
            new Destination("CA", "Fairmont Royal York", "Tim Hortons", "TTC Toronto", "Roots"),
            new Destination("DE", "Hotel Adlon Berlin", "Mustafa's Gemuese Kebap", "BVG Berlin", "KaDeWe"),
            new Destination("PT", "Pestana Palace Lisboa", "Time Out Market Lisboa", "Carris Lisboa", "Fnac Chiado"),
            new Destination("KR", "Lotte Hotel Seoul", "Myeongdong Kyoja", "T-money Seoul", "Lotte Department Store"));

    /** Countries for trips and for injected fraud: one neutral list, so no country is treated as inherently risky. */
    static final List<String> COUNTRIES = DESTINATIONS.stream().map(Destination::country).toList();

    static final List<ForeignShop> FOREIGN_SHOPS = List.of(
            new ForeignShop("ASOS", "GB"), new ForeignShop("Zalando", "DE"), new ForeignShop("Uniqlo Japan", "JP"),
            new ForeignShop("Decathlon France", "FR"), new ForeignShop("Zara Espana", "ES"), new ForeignShop("Yoox Italia", "IT"));

    static final String TOLL_ROAD = "E-ZPass";
    static final String ROADSIDE_HOTEL = "Holiday Inn Express";

    // Fictional merchants for fraud.
    static final List<String> CARD_TESTING_PROBES = List.of(
            "Bright Futures Charity", "PixelGames Online", "QuickTune Downloads", "Daily Deals Shop", "SnackHub Vending");
    static final List<String> CARD_TESTING_CASH_OUTS = List.of("ElectroMart Online", "GiftCardZone", "Gadget Outlet Direct");
    static final List<String> FRAUD_ABROAD = List.of("Online Electronics Store", "Luxury Goods Outlet", "Digital Gift Cards");

    private Merchants() {
    }
}
