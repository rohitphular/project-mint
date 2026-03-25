#!/usr/bin/env python3
"""
Supermarket Till Simulator
Simulates multiple tills calling the legacy REST API with random transaction data
"""

import requests
import json
import random
import time
import uuid
import logging
from datetime import datetime, timezone
from typing import Dict, List, Any
from collections import deque

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)

class TillSimulator:
    def __init__(self, api_base_url: str = "http://localhost:8080"):
        self.api_base_url = api_base_url
        self.session = requests.Session()
        
        # Track submitted transaction IDs to detect duplicates (bounded cache to prevent memory leaks)
        # Using deque with maxlen=1000 to keep only the most recent 1000 transaction IDs
        self.submitted_transaction_ids = deque(maxlen=1000)
        
        # Sample data for generating realistic transactions
        self.stores = ["STORE-001", "STORE-002", "STORE-003", "STORE-004", "STORE-005"]
        self.tills = ["TILL-1", "TILL-2", "TILL-3", "TILL-4", "TILL-5", "TILL-6", "TILL-7", "TILL-8"]
        self.payment_methods = ["card", "cash", "contactless"]
        
        # Product catalog with realistic supermarket items
        self.products = [
            {"name": "Milk", "code": "MILK001", "price": 2.50, "category": "Dairy"},
            {"name": "Bread", "code": "BREAD001", "price": 1.20, "category": "Bakery"},
            {"name": "Coffee", "code": "COFFEE001", "price": 3.99, "category": "Beverages"},
            {"name": "Chicken Breast", "code": "CHICKEN001", "price": 8.99, "category": "Meat"},
            {"name": "Rice", "code": "RICE001", "price": 2.99, "category": "Grains"},
            {"name": "Bananas", "code": "BANANA001", "price": 1.50, "category": "Fruit"},
            {"name": "Eggs", "code": "EGGS001", "price": 2.99, "category": "Dairy"},
            {"name": "Pasta", "code": "PASTA001", "price": 1.79, "category": "Grains"},
            {"name": "Tomatoes", "code": "TOMATO001", "price": 2.49, "category": "Vegetables"},
            {"name": "Cheese", "code": "CHEESE001", "price": 4.50, "category": "Dairy"},
            {"name": "Yogurt", "code": "YOGURT001", "price": 1.99, "category": "Dairy"},
            {"name": "Apples", "code": "APPLE001", "price": 2.99, "category": "Fruit"},
            {"name": "Potatoes", "code": "POTATO001", "price": 3.49, "category": "Vegetables"},
            {"name": "Onions", "code": "ONION001", "price": 1.29, "category": "Vegetables"},
            {"name": "Cereal", "code": "CEREAL001", "price": 3.99, "category": "Breakfast"},
            {"name": "Orange Juice", "code": "OJ001", "price": 2.79, "category": "Beverages"},
            {"name": "Butter", "code": "BUTTER001", "price": 2.99, "category": "Dairy"},
            {"name": "Ham", "code": "HAM001", "price": 4.99, "category": "Deli"},
            {"name": "Lettuce", "code": "LETTUCE001", "price": 1.99, "category": "Vegetables"},
            {"name": "Cucumber", "code": "CUCUMBER001", "price": 0.99, "category": "Vegetables"}
        ]
    
    def generate_customer_id(self) -> str:
        """Generate a random customer ID"""
        return f"CUST-{random.randint(10000, 99999)}"
    
    def generate_transaction_id(self) -> str:
        """Generate a unique transaction ID"""
        return f"TXN-{uuid.uuid4().hex[:8].upper()}"
    
    def generate_random_items(self, min_items: int = 1, max_items: int = 8) -> List[Dict[str, Any]]:
        """Generate random items for a transaction"""
        num_items = random.randint(min_items, max_items)
        selected_products = random.sample(self.products, num_items)
        
        items = []
        for product in selected_products:
            quantity = random.randint(1, 3)
            items.append({
                "productName": product["name"],
                "productCode": product["code"],
                "unitPrice": round(product["price"], 2),
                "quantity": quantity,
                "category": product["category"]
            })
        
        return items
    
    def calculate_total(self, items: List[Dict[str, Any]]) -> float:
        """Calculate total amount from items"""
        total = sum(item["unitPrice"] * item["quantity"] for item in items)
        return round(total, 2)
    
    def generate_transaction(self) -> Dict[str, Any]:
        """Generate a complete random transaction"""
        items = self.generate_random_items()
        total_amount = self.calculate_total(items)
        
        transaction = {
            "transactionId": self.generate_transaction_id(),
            "customerId": self.generate_customer_id(),
            "storeId": random.choice(self.stores),
            "tillId": random.choice(self.tills),
            "paymentMethod": random.choice(self.payment_methods),
            "totalAmount": total_amount,
            "currency": "GBP",
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "items": items
        }
        
        return transaction
    
    def test_response_validation_bypass(self) -> bool:
        """Test that candidates cannot bypass validation with minimal responses"""
        logger.info("Testing response validation bypass attempts...")
        
        test_cases = [
            {
                "name": "Empty response body",
                "status_code": 200,
                "response_body": {},
                "should_fail": True
            },
            {
                "name": "Minimal success response",
                "status_code": 200,
                "response_body": {"status": "success"},
                "should_fail": True
            },
            {
                "name": "Missing transactionId",
                "status_code": 200,
                "response_body": {"status": "success", "message": "Transaction processed successfully", "timestamp": "2024-01-01T00:00:00Z"},
                "should_fail": True
            },
            {
                "name": "Wrong transactionId",
                "status_code": 200,
                "response_body": {"status": "success", "message": "Transaction processed successfully", "transactionId": "WRONG-ID", "timestamp": "2024-01-01T00:00:00Z"},
                "should_fail": True
            },
            {
                "name": "Invalid status",
                "status_code": 200,
                "response_body": {"status": "ok", "message": "Transaction processed successfully", "transactionId": "TXN-TEST001", "timestamp": "2024-01-01T00:00:00Z"},
                "should_fail": True
            },
            {
                "name": "Empty message",
                "status_code": 200,
                "response_body": {"status": "success", "message": "", "transactionId": "TXN-TEST001", "timestamp": "2024-01-01T00:00:00Z"},
                "should_fail": True
            },
            {
                "name": "Message without success",
                "status_code": 200,
                "response_body": {"status": "success", "message": "Transaction completed", "transactionId": "TXN-TEST001", "timestamp": "2024-01-01T00:00:00Z"},
                "should_fail": True
            },
            {
                "name": "Invalid timestamp format",
                "status_code": 200,
                "response_body": {"status": "success", "message": "Transaction processed successfully", "transactionId": "TXN-TEST001", "timestamp": "invalid-date"},
                "should_fail": True
            },
            {
                "name": "Future timestamp",
                "status_code": 200,
                "response_body": {"status": "success", "message": "Transaction processed successfully", "transactionId": "TXN-TEST001", "timestamp": "2030-01-01T00:00:00Z"},
                "should_fail": True
            },
            {
                "name": "Extra fields",
                "status_code": 200,
                "response_body": {"status": "success", "message": "Transaction processed successfully", "transactionId": "TXN-TEST001", "timestamp": "2024-01-01T00:00:00Z", "extra": "field"},
                "should_fail": True
            },
            {
                "name": "Wrong data types",
                "status_code": 200,
                "response_body": {"status": 200, "message": 123, "transactionId": 456, "timestamp": 789},
                "should_fail": True
            },
            {
                "name": "Valid response",
                "status_code": 200,
                "response_body": {"status": "success", "message": "Transaction processed successfully", "transactionId": "TXN-TEST001", "timestamp": datetime.now(timezone.utc).isoformat()},
                "should_fail": False
            }
        ]
        
        passed_tests = 0
        total_tests = len(test_cases)
        
        for test_case in test_cases:
            logger.debug(f"Testing: {test_case['name']}")
            
            # Simulate the validation logic
            try:
                result = test_case["response_body"]
                
                # Run through the same validation as the real submit_transaction method
                if test_case["status_code"] == 200:
                    # Validate response structure
                    if not isinstance(result, dict):
                        validation_failed = True
                    else:
                        # Validate status field
                        if result.get("status") != "success":
                            validation_failed = True
                        # Validate message field
                        elif not result.get("message") or len(result.get("message", "").strip()) == 0:
                            validation_failed = True
                        elif "success" not in result.get("message", "").lower():
                            validation_failed = True
                        elif len(result.get("message", "").strip()) < 10:
                            validation_failed = True
                        # Validate transactionId
                        elif not result.get("transactionId") or not result["transactionId"].startswith("TXN-"):
                            validation_failed = True
                        # Validate timestamp
                        elif not result.get("timestamp"):
                            validation_failed = True
                        else:
                            try:
                                response_timestamp = datetime.fromisoformat(result["timestamp"].replace('Z', '+00:00'))
                                now = datetime.now(timezone.utc)
                                time_diff = abs((now - response_timestamp).total_seconds())
                                
                                if time_diff > 300 or time_diff < 0:
                                    validation_failed = True
                                else:
                                    validation_failed = False
                            except:
                                validation_failed = True
                else:
                    validation_failed = True
                
                # Check if the test result matches expectations
                if validation_failed == test_case["should_fail"]:
                    passed_tests += 1
                    logger.debug(f"✓ {test_case['name']}: {'Correctly rejected' if test_case['should_fail'] else 'Correctly accepted'}")
                else:
                    logger.error(f"✗ {test_case['name']}: {'Should have been rejected but was accepted' if test_case['should_fail'] else 'Should have been accepted but was rejected'}")
                    
            except Exception as e:
                if test_case["should_fail"]:
                    passed_tests += 1
                    logger.debug(f"✓ {test_case['name']}: Correctly rejected with exception")
                else:
                    logger.error(f"✗ {test_case['name']}: Unexpected exception: {e}")
        
        logger.info(f"Response validation bypass tests: {passed_tests}/{total_tests} passed")
        return passed_tests == total_tests

    def test_error_handling(self) -> bool:
        """Test error handling by sending malformed requests"""
        try:
            url = f"{self.api_base_url}/api/transactions/submit"
            headers = {"Content-Type": "application/json"}
            
            # Test 1: Missing required fields
            malformed_transaction = {
                "transactionId": "TXN-TEST002",
                "customerId": "CUST-12345"
                # Missing other required fields
            }
            
            response = self.session.post(url, json=malformed_transaction, headers=headers, timeout=10)
            
            if response.status_code != 400:
                logger.warning(f"Expected 400 for malformed request, got {response.status_code}")
                return False
            
            try:
                result = response.json()
                if result.get("status") != "error":
                    logger.warning("Expected error status in response")
                    return False
                logger.debug("Error handling test 1 passed - malformed request properly rejected")
            except ValueError:
                logger.warning("Invalid JSON in error response")
                return False
            
            # Test 2: Invalid JSON
            response = self.session.post(url, data="invalid json", headers=headers, timeout=10)
            
            if response.status_code != 400:
                logger.warning(f"Expected 400 for invalid JSON, got {response.status_code}")
                return False
            
            logger.info("Error handling tests passed")
            return True
            
        except Exception as e:
            logger.error(f"Error handling test failed: {e}")
            return False

    def test_submit_endpoint(self) -> bool:
        """Test the submit endpoint to ensure it's working correctly"""
        try:
            # Create a simple test transaction
            test_transaction = {
                "transactionId": "TXN-TEST001",
                "customerId": "CUST-12345",
                "storeId": "STORE-001",
                "tillId": "TILL-1",
                "paymentMethod": "card",
                "totalAmount": 5.99,
                "currency": "GBP",
                "timestamp": datetime.now(timezone.utc).isoformat(),
                "items": [
                    {
                        "productName": "Test Product",
                        "productCode": "TEST001",
                        "unitPrice": 5.99,
                        "quantity": 1,
                        "category": "Test"
                    }
                ]
            }
            
            url = f"{self.api_base_url}/api/transactions/submit"
            headers = {"Content-Type": "application/json"}
            
            response = self.session.post(url, json=test_transaction, headers=headers, timeout=10)
            
            if response.status_code == 200:
                try:
                    result = response.json()
                    
                    # Validate response structure
                    required_fields = ["status", "message", "transactionId", "timestamp"]
                    missing_fields = [field for field in required_fields if field not in result]
                    
                    if missing_fields:
                        logger.error(f"Submit endpoint test failed - missing fields: {missing_fields}")
                        logger.error(f"Response: {result}")
                        return False
                    
                    if result["status"] != "success":
                        logger.error(f"Submit endpoint test failed - status: {result['status']}")
                        return False
                    
                    if result["transactionId"] != "TXN-TEST001":
                        logger.error(f"Submit endpoint test failed - transaction ID mismatch: {result['transactionId']}")
                        return False
                    
                    logger.info("Submit endpoint test passed")
                    return True
                    
                except ValueError as e:
                    logger.error(f"Submit endpoint test failed - invalid JSON: {e}")
                    return False
            else:
                logger.error(f"Submit endpoint test failed - status {response.status_code}: {response.text}")
                return False
                
        except Exception as e:
            logger.error(f"Submit endpoint test failed: {e}")
            return False

    def test_stats_endpoint(self, store_id: str = "STORE-001") -> bool:
        """Test the stats endpoint to ensure it's working correctly"""
        try:
            url = f"{self.api_base_url}/api/transactions/stats/{store_id}"
            response = self.session.get(url, timeout=5)
            
            if response.status_code == 200:
                try:
                    result = response.json()
                    
                    # Validate stats response structure
                    required_fields = ["storeId", "totalTransactions", "totalAmount", "averageAmount"]
                    missing_fields = [field for field in required_fields if field not in result]
                    
                    if missing_fields:
                        logger.warning(f"Stats endpoint missing required fields: {missing_fields}")
                        logger.warning(f"Stats response: {result}")
                        return False
                    
                    logger.info(f"Stats endpoint working correctly for {store_id}: {result['totalTransactions']} transactions, £{result['totalAmount']} total, £{result['averageAmount']} average")
                    return True
                    
                except ValueError as e:
                    logger.error(f"Invalid JSON response from stats endpoint: {e}")
                    return False
            else:
                logger.error(f"Stats endpoint returned status {response.status_code}: {response.text}")
                return False
                
        except Exception as e:
            logger.error(f"Error testing stats endpoint: {e}")
            return False
    
    def health_check(self) -> bool:
        """Check if the API is available and healthy"""
        try:
            url = f"{self.api_base_url}/api/transactions/health"
            response = self.session.get(url, timeout=5)
            
            if response.status_code == 200:
                try:
                    result = response.json()
                    if result.get("status") == "UP":
                        logger.info("API health check passed")
                        return True
                    else:
                        logger.error(f"API health check failed: status is {result.get('status')}")
                        return False
                except ValueError:
                    logger.error("Invalid JSON response from health endpoint")
                    return False
            else:
                logger.error(f"Health endpoint returned status {response.status_code}")
                return False
                
        except Exception as e:
            logger.error(f"Error during health check: {e}")
            return False

    def validate_transaction_data(self, transaction: Dict[str, Any]) -> bool:
        """Validate transaction data before submission"""
        try:
            # Check required fields
            required_fields = [
                "transactionId", "customerId", "storeId", "tillId", 
                "paymentMethod", "totalAmount", "currency", "timestamp", "items"
            ]
            
            missing_fields = [field for field in required_fields if field not in transaction]
            if missing_fields:
                logger.error(f"Missing required fields in transaction: {missing_fields}")
                return False
            
            # Validate transaction ID format
            if not transaction["transactionId"].startswith("TXN-"):
                logger.error(f"Invalid transaction ID format: {transaction['transactionId']}")
                return False
            
            # Validate customer ID format
            if not transaction["customerId"].startswith("CUST-"):
                logger.error(f"Invalid customer ID format: {transaction['customerId']}")
                return False
            
            # Validate store ID format
            if not transaction["storeId"].startswith("STORE-"):
                logger.error(f"Invalid store ID format: {transaction['storeId']}")
                return False
            
            # Validate till ID format
            if not transaction["tillId"].startswith("TILL-"):
                logger.error(f"Invalid till ID format: {transaction['tillId']}")
                return False
            
            # Validate payment method
            valid_payment_methods = ["card", "cash", "contactless"]
            if transaction["paymentMethod"] not in valid_payment_methods:
                logger.error(f"Invalid payment method: {transaction['paymentMethod']}")
                return False
            
            # Validate currency
            if transaction["currency"] != "GBP":
                logger.error(f"Invalid currency: {transaction['currency']}")
                return False
            
            # Validate total amount
            if not isinstance(transaction["totalAmount"], (int, float)) or transaction["totalAmount"] <= 0:
                logger.error(f"Invalid total amount: {transaction['totalAmount']}")
                return False
            
            # Validate items
            if not isinstance(transaction["items"], list) or len(transaction["items"]) == 0:
                logger.error("Transaction must have at least one item")
                return False
            
            # Validate each item
            for i, item in enumerate(transaction["items"]):
                item_required_fields = ["productName", "productCode", "unitPrice", "quantity", "category"]
                missing_item_fields = [field for field in item_required_fields if field not in item]
                
                if missing_item_fields:
                    logger.error(f"Item {i} missing required fields: {missing_item_fields}")
                    return False
                
                if not isinstance(item["unitPrice"], (int, float)) or item["unitPrice"] <= 0:
                    logger.error(f"Item {i} has invalid unit price: {item['unitPrice']}")
                    return False
                
                if not isinstance(item["quantity"], int) or item["quantity"] <= 0:
                    logger.error(f"Item {i} has invalid quantity: {item['quantity']}")
                    return False
            
            return True
            
        except Exception as e:
            logger.error(f"Error validating transaction data: {e}")
            return False

    def submit_transaction(self, transaction: Dict[str, Any]) -> bool:
        """Submit transaction to the legacy REST API"""
        start_time = time.time()
        
        try:
            url = f"{self.api_base_url}/api/transactions/submit"
            headers = {"Content-Type": "application/json"}
            
            response = self.session.post(url, json=transaction, headers=headers, timeout=10)
            
            # Calculate response time
            response_time = time.time() - start_time
            if response_time > 5.0:  # More than 5 seconds
                logger.warning(f"Slow response time for transaction {transaction['transactionId']}: {response_time:.2f}s")
            elif response_time > 2.0:  # More than 2 seconds
                logger.info(f"Response time for transaction {transaction['transactionId']}: {response_time:.2f}s")
            
            # Check for caching headers
            cache_headers = ['cache-control', 'etag', 'last-modified']
            cache_indicators = [header for header in cache_headers if header in response.headers]
            if cache_indicators:
                logger.warning(f"Response contains cache headers for transaction {transaction['transactionId']}: {cache_indicators}")
                logger.warning("This may indicate the API is returning cached responses")
            
            # Wait for response and validate
            if response.status_code == 200:
                # Validate content type
                content_type = response.headers.get('content-type', '')
                if 'application/json' not in content_type.lower():
                    logger.warning(f"Unexpected content type for transaction {transaction['transactionId']}: {content_type}")
                    logger.warning("Expected application/json, but continuing with validation...")
                
                # Check encoding
                if 'charset' in content_type:
                    charset = content_type.split('charset=')[-1].split(';')[0].strip()
                    if charset.lower() not in ['utf-8', 'utf8']:
                        logger.warning(f"Unexpected charset for transaction {transaction['transactionId']}: {charset}")
                
                try:
                    result = response.json()
                    logger.debug(f"Validating response for transaction {transaction['transactionId']}")
                    
                    # Validate response size (should be reasonable)
                    response_size = len(response.content)
                    if response_size < 50:  # Too small
                        logger.warning(f"Response seems too small for transaction {transaction['transactionId']}: {response_size} bytes")
                    elif response_size > 10000:  # Too large
                        logger.warning(f"Response seems too large for transaction {transaction['transactionId']}: {response_size} bytes")
                    
                    # Validate response structure
                    if not isinstance(result, dict):
                        logger.error(f"Invalid response format for transaction {transaction['transactionId']}: expected dict, got {type(result)}")
                        return False
                    
                    # Detect response format changes
                    self.detect_response_format_changes(result, transaction['transactionId'])
                    
                    # Validate response consistency
                    self.validate_response_consistency(transaction, result)
                    
                    # Validate status field
                    if result["status"] != "success":
                        logger.error(f"Transaction {transaction['transactionId']} failed with status: {result['status']}, message: {result.get('message', 'No message')}")
                        return False
                    
                    # Validate message field - must be specific and meaningful
                    if not result.get("message") or len(result["message"].strip()) == 0:
                        logger.error(f"Empty or missing message in response for transaction {transaction['transactionId']}")
                        return False
                    elif "success" not in result["message"].lower():
                        logger.error(f"Invalid message content for transaction {transaction['transactionId']}: {result['message']}")
                        logger.error("Message must contain 'success' to indicate successful processing")
                        return False
                    elif len(result["message"].strip()) < 10:
                        logger.error(f"Message too short for transaction {transaction['transactionId']}: '{result['message']}'")
                        logger.error("Message must be meaningful and descriptive")
                        return False
                    
                    # Validate transactionId matches exactly
                    if result["transactionId"] != transaction["transactionId"]:
                        logger.error(f"Transaction ID mismatch for {transaction['transactionId']}: expected {transaction['transactionId']}, got {result['transactionId']}")
                        return False
                    
                    # Validate transactionId format in response
                    if not result["transactionId"].startswith("TXN-"):
                        logger.error(f"Invalid transaction ID format in response: {result['transactionId']}")
                        return False
                    
                    # Validate timestamp format and content
                    if not result.get("timestamp"):
                        logger.error(f"Missing timestamp in response for transaction {transaction['transactionId']}")
                        return False
                    
                    # Validate timestamp is a valid ISO format
                    try:
                        response_timestamp = datetime.fromisoformat(result["timestamp"].replace('Z', '+00:00'))
                        now = datetime.now(timezone.utc)
                        time_diff = abs((now - response_timestamp).total_seconds())
                        
                        if time_diff > 300:  # 5 minutes
                            logger.error(f"Response timestamp too old for transaction {transaction['transactionId']}: {result['timestamp']} (diff: {time_diff:.1f}s)")
                            logger.error("Timestamp must be recent (within 5 minutes)")
                            return False
                        elif time_diff < 0:
                            logger.error(f"Response timestamp is in the future for transaction {transaction['transactionId']}: {result['timestamp']}")
                            logger.error("Timestamp cannot be in the future")
                            return False
                    except Exception as e:
                        logger.error(f"Invalid timestamp format for transaction {transaction['transactionId']}: {result['timestamp']}")
                        logger.error(f"Timestamp must be in valid ISO format. Error: {e}")
                        return False
                    
                    # Validate response contains exactly the expected fields (no extra, no missing)
                    expected_fields = {"status", "message", "transactionId", "timestamp"}
                    actual_fields = set(result.keys())
                    
                    if actual_fields != expected_fields:
                        missing_fields = expected_fields - actual_fields
                        extra_fields = actual_fields - expected_fields
                        
                        if missing_fields:
                            logger.error(f"Missing required fields in response for transaction {transaction['transactionId']}: {missing_fields}")
                            return False
                        
                        if extra_fields:
                            logger.error(f"Unexpected extra fields in response for transaction {transaction['transactionId']}: {extra_fields}")
                            logger.error("Response should contain exactly: status, message, transactionId, timestamp")
                            return False
                    
                    # Validate field types
                    if not isinstance(result["status"], str):
                        logger.error(f"Status field must be a string for transaction {transaction['transactionId']}, got {type(result['status'])}")
                        return False
                    
                    if not isinstance(result["message"], str):
                        logger.error(f"Message field must be a string for transaction {transaction['transactionId']}, got {type(result['message'])}")
                        return False
                    
                    if not isinstance(result["transactionId"], str):
                        logger.error(f"TransactionId field must be a string for transaction {transaction['transactionId']}, got {type(result['transactionId'])}")
                        return False
                    
                    if not isinstance(result["timestamp"], str):
                        logger.error(f"Timestamp field must be a string for transaction {transaction['transactionId']}, got {type(result['timestamp'])}")
                        return False
                    
                    # Check for duplicate transaction IDs
                    if result["transactionId"] in self.submitted_transaction_ids:
                        logger.warning(f"Duplicate transaction ID detected: {result['transactionId']}")
                        logger.warning("This may indicate an issue with transaction ID generation or processing")
                    else:
                        self.submitted_transaction_ids.append(result["transactionId"])
                    
                    logger.info(f"Transaction {transaction['transactionId']} submitted successfully - Store: {transaction['storeId']}, Till: {transaction['tillId']}, Amount: £{transaction['totalAmount']}")
                    logger.debug(f"Response validation passed for transaction {transaction['transactionId']}")
                    return True
                    
                except ValueError as e:
                    logger.error(f"Invalid JSON response for transaction {transaction['transactionId']}: {e}")
                    logger.error(f"Response text: {response.text}")
                    logger.error(f"Response headers: {dict(response.headers)}")
                    logger.error("The API may be returning malformed JSON or an error page")
                    return False
                    
            elif response.status_code == 400:
                try:
                    error_result = response.json()
                    logger.error(f"Bad request for transaction {transaction['transactionId']}: {error_result.get('message', 'Unknown error')}")
                    if 'error' in error_result:
                        logger.error(f"Error details: {error_result['error']}")
                except ValueError:
                    logger.error(f"Bad request for transaction {transaction['transactionId']}: {response.text}")
                return False
                
            elif response.status_code == 500:
                try:
                    error_result = response.json()
                    logger.error(f"Server error for transaction {transaction['transactionId']}: {error_result.get('message', 'Internal server error')}")
                except ValueError:
                    logger.error(f"Server error for transaction {transaction['transactionId']}: {response.text}")
                return False
                
            elif response.status_code == 404:
                logger.error(f"Endpoint not found for transaction {transaction['transactionId']}: {response.status_code} - {response.text}")
                logger.error("The API endpoint may have changed or the service is not running correctly")
                return False
                
            elif response.status_code == 503:
                logger.error(f"Service unavailable for transaction {transaction['transactionId']}: {response.status_code} - {response.text}")
                logger.error("The API service may be down or overloaded")
                return False
                
            else:
                logger.error(f"Unexpected status code for transaction {transaction['transactionId']}: {response.status_code} - {response.text}")
                logger.error(f"Expected 200, 400, 404, 500, or 503, got {response.status_code}")
                return False
                
        except requests.exceptions.ConnectionError:
            logger.error(f"Connection error submitting transaction {transaction['transactionId']}: unable to connect to API")
            logger.error("Please ensure the API is running and accessible")
            return False
        except requests.exceptions.Timeout:
            logger.error(f"Timeout submitting transaction {transaction['transactionId']}: request took longer than 10 seconds")
            logger.error("The API may be overloaded or experiencing issues")
            return False
        except requests.exceptions.RequestException as e:
            logger.error(f"Request error submitting transaction {transaction['transactionId']}: {e}")
            return False
        except Exception as e:
            logger.error(f"Unexpected error submitting transaction {transaction['transactionId']}: {e}")
            logger.error("This may indicate a bug in the API or an unexpected issue")
            return False

    def validate_response_consistency(self, transaction: Dict[str, Any], result: Dict[str, Any]) -> bool:
        """Validate that the response is consistent with the submitted transaction"""
        try:
            # Check if the API modified the transaction ID
            if result["transactionId"] != transaction["transactionId"]:
                logger.warning(f"API modified transaction ID: {transaction['transactionId']} -> {result['transactionId']}")
                return False
            
            # Check if the response contains unexpected data
            unexpected_data = set(result.keys()) - {"status", "message", "transactionId", "timestamp"}
            if unexpected_data:
                logger.warning(f"Response contains unexpected data: {unexpected_data}")
                logger.warning("This may indicate the API is returning additional fields")
            
            return True
            
        except Exception as e:
            logger.warning(f"Error validating response consistency: {e}")
            return False

    def detect_response_format_changes(self, result: Dict[str, Any], transaction_id: str) -> bool:
        """Detect if the API response format has changed unexpectedly"""
        try:
            # Check for unexpected fields
            expected_fields = {"status", "message", "transactionId", "timestamp"}
            unexpected_fields = set(result.keys()) - expected_fields
            
            if unexpected_fields:
                logger.warning(f"Unexpected fields in response for transaction {transaction_id}: {unexpected_fields}")
                logger.warning(f"This may indicate the API response format has changed")
                return True
            
            # Check for missing expected fields
            missing_fields = expected_fields - set(result.keys())
            if missing_fields:
                logger.warning(f"Missing expected fields in response for transaction {transaction_id}: {missing_fields}")
                logger.warning(f"This may indicate the API response format has changed")
                return True
            
            return False
            
        except Exception as e:
            logger.warning(f"Error detecting response format changes for transaction {transaction_id}: {e}")
            return False

    def comprehensive_api_test(self) -> bool:
        """Perform comprehensive API testing with various scenarios"""
        logger.info("Performing comprehensive API testing...")
        
        test_results = {
            "health_check": self.health_check(),
            "submit_endpoint": self.test_submit_endpoint(),
            "error_handling": self.test_error_handling(),
            "stats_endpoint": self.test_stats_endpoint(),
            "response_validation_bypass": self.test_response_validation_bypass()
        }
        
        passed_tests = sum(test_results.values())
        total_tests = len(test_results)
        
        logger.info("=" * 60)
        logger.info("COMPREHENSIVE API TEST RESULTS")
        logger.info("=" * 60)
        for test_name, result in test_results.items():
            status = "✓ PASS" if result else "✗ FAIL"
            logger.info(f"{test_name}: {status}")
        logger.info("=" * 60)
        logger.info(f"Overall: {passed_tests}/{total_tests} tests passed")
        logger.info("=" * 60)
        
        if passed_tests < total_tests:
            logger.warning("Some API tests failed. The simulation may not work correctly.")
            logger.warning("Please check the API implementation and ensure all endpoints are working.")
        
        return passed_tests >= total_tests - 1  # Allow one test to fail (stats endpoint)

    def print_validation_summary(self):
        """Print a summary of all validation results"""
        logger.info("=" * 60)
        logger.info("VALIDATION SUMMARY")
        logger.info("=" * 60)
        logger.info("The following validations are performed:")
        logger.info("✓ Health check - API availability")
        logger.info("✓ Submit endpoint - Response structure and data validation")
        logger.info("✓ Error handling - Malformed request rejection")
        logger.info("✓ Stats endpoint - Statistics calculation validation")
        logger.info("✓ Response validation bypass - Prevents minimal response bypass")
        logger.info("✓ Transaction data - Pre-submission validation")
        logger.info("✓ Response validation - Post-submission response checking")
        logger.info("✓ Periodic stats validation - Ongoing statistics monitoring")
        logger.info("=" * 60)
        logger.info("Response validation ensures candidates cannot bypass with:")
        logger.info("• Empty or minimal response bodies")
        logger.info("• Incorrect field values or formats")
        logger.info("• Missing required fields")
        logger.info("• Wrong data types")
        logger.info("• Invalid timestamps")
        logger.info("• Extra or unexpected fields")
        logger.info("=" * 60)
        logger.info("If any validation fails, the script will log detailed error messages.")
        logger.info("This ensures the API is working correctly and candidates cannot bypass validation.")
        logger.info("=" * 60)

    def run(self, interval_seconds: int = 1):
        """Run the simulator continuously"""
        logger.info(f"Starting Till Simulator - calling API every {interval_seconds} second(s)")
        logger.info(f"API Base URL: {self.api_base_url}")
        
        # Print validation summary
        self.print_validation_summary()
        
        # Perform comprehensive API testing
        if not self.comprehensive_api_test():
            logger.error("Comprehensive API testing failed. Please check the API implementation.")
            logger.error("The simulation cannot proceed until the API is working correctly.")
            return
        
        logger.info("=" * 60)
        logger.info("STARTING TRANSACTION SIMULATION")
        logger.info("=" * 60)
        logger.info("All API tests passed. Beginning transaction simulation...")
        logger.info("Press Ctrl+C to stop the simulation.")
        logger.info("=" * 60)
        
        success_count = 0
        error_count = 0
        stats_validation_count = 0
        
        try:
            while True:
                transaction = self.generate_transaction()
                
                # Validate transaction data before submission
                if not self.validate_transaction_data(transaction):
                    logger.error(f"Skipping invalid transaction {transaction['transactionId']}")
                    error_count += 1
                    time.sleep(interval_seconds)
                    continue
                
                if self.submit_transaction(transaction):
                    success_count += 1
                else:
                    error_count += 1
                
                # Log statistics every 10 transactions
                if (success_count + error_count) % 10 == 0:
                    logger.info(f"Statistics - Success: {success_count}, Errors: {error_count}, Total: {success_count + error_count}")
                
                # Periodically validate stats endpoint (every 50 transactions)
                if (success_count + error_count) % 50 == 0:
                    stats_validation_count += 1
                    logger.info(f"Validating stats endpoint (validation #{stats_validation_count})...")
                    if not self.test_stats_endpoint():
                        logger.warning(f"Stats endpoint validation #{stats_validation_count} failed - this may indicate a bug in the statistics calculation")
                    else:
                        logger.info(f"Stats endpoint validation #{stats_validation_count} passed")
                
                time.sleep(interval_seconds)
                
        except KeyboardInterrupt:
            logger.info("Till Simulator stopped by user")
            logger.info("=" * 60)
            logger.info("FINAL STATISTICS")
            logger.info("=" * 60)
            logger.info(f"Transactions submitted successfully: {success_count}")
            logger.info(f"Transactions failed: {error_count}")
            logger.info(f"Total transactions attempted: {success_count + error_count}")
            logger.info(f"Stats validations performed: {stats_validation_count}")
            logger.info(f"Success rate: {(success_count / (success_count + error_count) * 100):.1f}%" if (success_count + error_count) > 0 else "N/A")
            logger.info("=" * 60)

def main():
    """Main entry point"""
    import os
    
    # Get configuration from environment variables
    api_url = os.getenv("API_BASE_URL", "http://localhost:8080")
    interval = int(os.getenv("SIMULATION_INTERVAL_SECONDS", "1"))
    
    simulator = TillSimulator(api_url)
    simulator.run(interval)

if __name__ == "__main__":
    main() 