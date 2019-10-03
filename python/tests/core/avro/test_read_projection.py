# from .conftest import write_and_read
from iceberg.core import ManifestEntry, PartitionSpecParser, SchemaParser
from iceberg.core.avro import IcebergToAvro


def test_full_projection(iceberg_full_read_projection_schema):
    schema = SchemaParser.from_json({'type': 'struct', 'fields': [{'id': 1, 'name': 'account_id', 'required': False, 'type': 'long', 'doc': 'Lookup table: account_d'}, {'id': 2, 'name': 'subscrn_id', 'required': False, 'type': 'long'}, {'id': 3, 'name': 'is_in_free_trial', 'required': False, 'type': 'int'}, {'id': 4, 'name': 'subscrn_service_days', 'required': False, 'type': 'int'}, {'id': 5, 'name': 'subscrn_period_nbr', 'required': False, 'type': 'int'}, {'id': 6, 'name': 'account_service_days', 'required': False, 'type': 'int', 'doc': 'number of days subscriber has had service over all subscriptions'}, {'id': 7, 'name': 'account_period_nbr', 'required': False, 'type': 'int'}, {'id': 8, 'name': 'current_plan_id', 'required': False, 'type': 'int', 'doc': 'Lookup table: plan_d'}, {'id': 10, 'name': 'country_iso_code', 'required': False, 'type': 'string', 'doc': 'Registration country id. Lookup table: geo_country_d'}, {'id': 11, 'name': 'is_onhold', 'required': False, 'type': 'int', 'doc': 'Subscriptions can be put on hold due to suspected fraud or failure to pay. subscriber is no longer able to view and is no longer considered a member while on hold'}, {'id': 12, 'name': 'same_day_hold', 'required': False, 'type': 'int', 'doc': 'When a member goes on hold and off hold in the same day'}, {'id': 13, 'name': 'dateint', 'required': False, 'type': 'int', 'doc': 'Will be deprecated'}, {'id': 14, 'name': 'plan_rollup_id', 'required': False, 'type': 'int', 'doc': 'Lookup table: plan_rollup_d'}, {'id': 15, 'name': 'price_tier_code', 'required': False, 'type': 'string', 'doc': 'Lookup table: price_tier_d. You can also use plan_price_d to lookup the combination of (plan_rollup_id, price_tier_code, country_iso_code)'}, {'id': 16, 'name': 'latest_plan_change_date', 'required': False, 'type': 'int', 'doc': 'Members can upgrade or downgrade plans'}, {'id': 17, 'name': 'is_in_product_grace_period', 'required': False, 'type': 'int', 'doc': 'Members on grace period can continue to view, but they are not considered active members since they are not in good payment standing.'}, {'id': 18, 'name': 'is_in_member_cnt', 'required': False, 'type': 'int', 'doc': 'Total # of entitlements. An entitlement is the right to stream Netflix and excludes those on hold or in grace periods and can include both paid and free subs.'}, {'id': 19, 'name': 'is_grace_period_to_member_cnt', 'required': False, 'type': 'int', 'doc': 'Members that were on grace period before, but cleared out and become in good standing'}, {'id': 20, 'name': 'is_grace_period_to_on_hold', 'required': False, 'type': 'int', 'doc': 'Members that were on grace period (usually for 7 days), but they failed to provide a valid payment method so they placed on hold'}, {'id': 21, 'name': 'is_from_gp_start', 'required': False, 'type': 'int', 'doc': 'Grace period start date'}, {'id': 22, 'name': 'is_onhold_without_gp_end', 'required': False, 'type': 'int'}, {'id': 23, 'name': 'subscription_type', 'required': False, 'type': 'string', 'doc': 'Added with NIO project. Values: P (paying only), S (streaming only), PS (paying and streaming)'}, {'id': 24, 'name': 'connected_to_account_id', 'required': False, 'type': 'long', 'doc': 'Added with NIO project. This will only be populated when the paying accounts have logins. Possible values: subscription_type = P, connected_to_account_id = value or null, subscription_type = S, connected_to_account_id = null, subscription_type = PS, connected_to_account_id = null'}, {'id': 25, 'name': 'can_stream', 'required': False, 'type': 'int', 'doc': 'Added with NIO project. Ability to stream whether a member is in good standing or on grace period. Possible values: subscription_type = P, can_stream = 0, subscription_type = S, can_stream = 1, subscription_type = PS, can_stream = 0 or 1 based hold and grace period'}, {'id': 26, 'name': 'is_untethered_account', 'required': False, 'type': 'int', 'doc': 'Added with NIO project. This column is used to identify paying accounts that are not associated with logins. Logic used to populate it: (subscription_type = P, can_stream=0, is_in_member_cnt =1, connected_to_account_id = null) Possible values: subscription_type = P, is_untethered_account = 0 or 1, subscription_type = S, is_untethered_account = 0, subscription_type = PS, is_untethered_account = 0'}, {'id': 27, 'name': 'is_billing_paused', 'required': False, 'type': 'int', 'doc': 'Added with NIO project. Possible values: subscription_type = P, is_billing_paused = 0, subscription_type = S, is_billing_paused = 0 or 1, subscription_type = PS, is_billing_paused = 0.'}, {'id': 28, 'name': 'is_in_customer_count', 'required': False, 'type': 'int'}, {'id': 29, 'name': 'paid_category', 'required': False, 'type': 'string'}, {'id': 30, 'name': 'is_in_paid_member_cnt', 'required': False, 'type': 'int'}, {'id': 31, 'name': 'scale', 'required': False, 'type': 'string'}, {'id': 32, 'name': 'test_new_col', 'required': False, 'type': 'string', 'doc': 'new_column docs'}, {'id': 33, 'name': 'test_new_col_2', 'required': False, 'type': 'string', 'doc': 'new_column docs 2'}]})
    spec = PartitionSpecParser.from_json_fields(schema, 0, [{"name": "scale",
                                                             "transform": "identity",
                                                             "source-id": 31}])
    proj_schema = ManifestEntry.project_schema(spec.partition_type(), ["*"])
    print(IcebergToAvro.type_to_schema(proj_schema.as_struct(), "manifest_entry"))
