CREATE TABLE learning_profile (
    id UUID PRIMARY KEY,
    instrument_id UUID NOT NULL,
    sector VARCHAR(100) NOT NULL,
    business_type VARCHAR(120) NOT NULL,
    business_description TEXT NOT NULL,
    major_business_factors TEXT NOT NULL,
    common_price_drivers TEXT NOT NULL,
    important_risks TEXT NOT NULL,
    educational_observations TEXT NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_learning_profile_instrument UNIQUE (instrument_id),
    CONSTRAINT fk_learning_profile_instrument FOREIGN KEY (instrument_id) REFERENCES instrument(id)
);

INSERT INTO learning_profile (
    id, instrument_id, sector, business_type, business_description,
    major_business_factors, common_price_drivers, important_risks,
    educational_observations, updated_at
)
SELECT CAST('e7c43fd1-07e8-4c01-a101-000000000001' AS UUID), i.id,
       'Information Technology', 'IT services and consulting',
       'Provides software services, consulting, and technology solutions to business customers across industries.',
       'Technology spending by clients|Large deal wins and project execution|Hiring, utilization, and employee costs|Revenue mix across geographies and services',
       'Quarterly results and guidance|Global technology spending outlook|Currency movements|Large contract announcements',
       'Dependence on discretionary client spending|Competition for skilled employees|Project delays or cost overruns|Concentration in large clients or regions',
       'Compare revenue growth with operating margins over several quarters|Observe how management guidance differs from reported results|Separate company-specific news from broad technology-sector moves',
       CURRENT_TIMESTAMP
FROM instrument i WHERE i.exchange = 'NSE' AND i.symbol = 'TCS';

INSERT INTO learning_profile (
    id, instrument_id, sector, business_type, business_description,
    major_business_factors, common_price_drivers, important_risks,
    educational_observations, updated_at
)
SELECT CAST('e7c43fd1-07e8-4c01-a101-000000000002' AS UUID), i.id,
       'Financial Services', 'Private sector bank',
       'Provides banking services to individuals and businesses, including deposits, lending, payments, and related financial products.',
       'Deposit growth and funding mix|Loan growth and borrower quality|Net interest margin|Operating costs and branch or digital reach',
       'Quarterly asset-quality disclosures|Credit growth and deposit competition|Interest-rate expectations|Capital and regulatory updates',
       'Borrower defaults and rising provisions|Funding costs increasing faster than lending yields|Economic slowdown|Regulatory or cyber-security events',
       'Track loan growth alongside deposit growth|Review non-performing asset trends with provisioning coverage|Distinguish changes in reported profit from changes in asset quality',
       CURRENT_TIMESTAMP
FROM instrument i WHERE i.exchange = 'NSE' AND i.symbol = 'HDFCBANK';

INSERT INTO learning_profile (
    id, instrument_id, sector, business_type, business_description,
    major_business_factors, common_price_drivers, important_risks,
    educational_observations, updated_at
)
SELECT CAST('e7c43fd1-07e8-4c01-a101-000000000003' AS UUID), i.id,
       'Energy and Conglomerates', 'Diversified energy and consumer businesses',
       'Operates across energy, refining, retail, digital services, and other businesses, with different economics across segments.',
       'Segment-level revenue and margins|Energy and feedstock prices|Retail expansion and same-store activity|Capital expenditure and project execution',
       'Segment results and guidance|Changes in energy prices and refining margins|Telecom and retail competition|Large investment or financing announcements',
       'Commodity-price volatility|Large capital requirements|Execution risk across multiple businesses|Complexity that can make consolidated results harder to interpret',
       'Read segment disclosures rather than relying only on consolidated growth|Compare investment spending with the progress of new businesses|Consider how commodity cycles affect different segments differently',
       CURRENT_TIMESTAMP
FROM instrument i WHERE i.exchange = 'NSE' AND i.symbol = 'RELIANCE';
